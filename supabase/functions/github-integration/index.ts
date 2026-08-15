import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

interface RequestPayload {
  action: 'test' | 'repo' | 'branches' | 'read' | 'create' | 'update';
  owner?: string;
  repo?: string;
  branch?: string;
  path?: string;
  content?: string;
  message?: string;
}

interface GitHubRepo {
  name: string;
  full_name: string;
  owner: { login: string };
  default_branch: string;
  private: boolean;
  html_url: string;
}

interface GitHubBranch {
  name: string;
  commit: { sha: string };
}

interface GitHubContent {
  type: string;
  path: string;
  name: string;
  sha: string;
  content: string;
  encoding: string;
  commit?: { sha: string };
}

function maskToken(token: string): string {
  if (token.length <= 8) return '***';
  return token.slice(0, 4) + '...' + token.slice(-4);
}

function getGitHubHeaders(token: string): Record<string, string> {
  return {
    'Authorization': `Bearer ${token}`,
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'NaderAI-GitHub-Integration',
  };
}

async function callGitHub<T>(url: string, token: string, init?: RequestInit): Promise<{ ok: true; data: T; status: number; headers: Headers } | { ok: false; status: number; error: string }> {
  const res = await fetch(url, {
    ...init,
    headers: { ...getGitHubHeaders(token), ...(init?.headers || {}) },
  });

  if (res.status === 401) return { ok: false, status: 401, error: 'المصادقة مع GitHub فاشلة. تحقق من صلاحية التوكن.' };
  if (res.status === 403) return { ok: false, status: 403, error: 'ليست لديك الصلاحيات الكافية أو تم تجاوز الحد المسموح (Rate Limit).' };
  if (res.status === 404) return { ok: false, status: 404, error: 'المستودع أو الملف غير موجود.' };
  if (res.status === 422) return { ok: false, status: 422, error: 'بيانات غير صالحة. قد يكون الملف موجوداً بالفعل أو الـ SHA غير صحيح.' };
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    return { ok: false, status: res.status, error: `خطأ GitHub (${res.status}): ${text.slice(0, 200)}` };
  }

  const data = res.status === 204 ? (undefined as T) : await res.json() as T;
  return { ok: true, data, status: res.status, headers: res.headers };
}

function getRepoConfig(payload: RequestPayload): { owner: string; repo: string; branch: string } | null {
  const owner = payload.owner?.trim() || Deno.env.get('GITHUB_REPO_OWNER') || '';
  const repo = payload.repo?.trim() || Deno.env.get('GITHUB_REPO_NAME') || '';
  const branch = payload.branch?.trim() || Deno.env.get('GITHUB_DEFAULT_BRANCH') || 'main';
  if (!owner || !repo) return null;
  return { owner, repo, branch };
}

async function logOperation(
  db: any,
  op: { operation_type: string; file_path: string | null; branch_name: string; status: 'success' | 'failed'; error_message?: string | null; commit_sha?: string | null; admin_id?: string | null },
) {
  try {
    await db.from('github_operations_log').insert({
      operation_type: op.operation_type,
      file_path: op.file_path,
      branch_name: op.branch_name,
      status: op.status,
      error_message: op.error_message || null,
      commit_sha: op.commit_sha || null,
      admin_id: op.admin_id || null,
    });
  } catch {
    // Best-effort logging; do not fail the main request.
  }
}

async function saveConnectionMeta(
  db: any,
  cfg: { owner: string; repo: string; default_branch: string; status: 'connected' | 'disconnected'; permissions: string[] },
) {
  const { data: row } = await db.from('github_connection').select('id').limit(1).single();
  if (!row?.id) return;
  await db.from('github_connection').update({
    repo_owner: cfg.owner,
    repo_name: cfg.repo,
    default_branch: cfg.default_branch,
    connection_status: cfg.status,
    permissions: cfg.permissions,
    last_connected_at: cfg.status === 'connected' ? new Date().toISOString() : null,
  }).eq('id', row.id);
}

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  const token = Deno.env.get('GITHUB_TOKEN');
  if (!token) return errorResponse('لم يتم تكوين GitHub Token في البيئة.', 503);

  let payload: RequestPayload;
  try {
    payload = await req.json();
  } catch {
    return errorResponse('Invalid JSON', 400);
  }

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  const { data: { user } } = await db.auth.getUser(req.headers.get('Authorization')?.replace(/^Bearer\s+/i, '') || '');

  const cfg = getRepoConfig(payload);
  if (!cfg) return errorResponse('يجب تحديد مالك المستودع واسمه (owner/repo) أو ضبطهما في متغيرات البيئة.', 400);

  const { owner, repo, branch } = cfg;
  const base = `https://api.github.com/repos/${owner}/${repo}`;

  switch (payload.action) {
    case 'test': {
      const repoRes = await callGitHub<GitHubRepo>(base, token);
      if (!repoRes.ok) return errorResponse(repoRes.error, repoRes.status);

      const perms: string[] = [];
      if (repoRes.data.private !== undefined) perms.push('repo:read');
      const writeRes = await callGitHub<{ content: GitHubContent; commit: { sha: string } }>(`${base}/contents/__naderai_write_test__`, token, {
        method: 'PUT',
        body: JSON.stringify({ message: 'NaderAI write test', content: btoa('test'), branch }),
      });
      if (writeRes.ok) {
        perms.push('contents:write');
        await callGitHub(`${base}/contents/__naderai_write_test__`, token, {
          method: 'DELETE',
          body: JSON.stringify({ message: 'NaderAI remove write test', sha: writeRes.data.content.sha, branch }),
        });
      } else if (writeRes.status === 403) {
        perms.push('contents:read-only');
      } else if (writeRes.status === 404) {
        perms.push('contents:read');
      }

      await saveConnectionMeta(db, {
        owner: repoRes.data.owner.login,
        repo: repoRes.data.name,
        default_branch: repoRes.data.default_branch,
        status: 'connected',
        permissions: perms,
      });

      return jsonResponse({
        success: true,
        owner: repoRes.data.owner.login,
        repo: repoRes.data.name,
        full_name: repoRes.data.full_name,
        default_branch: repoRes.data.default_branch,
        private: repoRes.data.private,
        html_url: repoRes.data.html_url,
        permissions: perms,
        token_masked: maskToken(token),
      });
    }

    case 'repo': {
      const repoRes = await callGitHub<GitHubRepo>(base, token);
      if (!repoRes.ok) return errorResponse(repoRes.error, repoRes.status);
      return jsonResponse({
        success: true,
        owner: repoRes.data.owner.login,
        repo: repoRes.data.name,
        full_name: repoRes.data.full_name,
        default_branch: repoRes.data.default_branch,
        private: repoRes.data.private,
        html_url: repoRes.data.html_url,
      });
    }

    case 'branches': {
      const branchesRes = await callGitHub<GitHubBranch[]>(`${base}/branches`, token);
      if (!branchesRes.ok) {
        await logOperation(db, { operation_type: 'branches', file_path: null, branch_name: branch, status: 'failed', error_message: branchesRes.error, admin_id: user?.id ?? null });
        return errorResponse(branchesRes.error, branchesRes.status);
      }
      await logOperation(db, { operation_type: 'branches', file_path: null, branch_name: branch, status: 'success', admin_id: user?.id ?? null });
      return jsonResponse({ success: true, branches: branchesRes.data.map((b) => ({ name: b.name, last_commit_sha: b.commit.sha })) });
    }

    case 'read': {
      const path = payload.path?.trim();
      if (!path) return errorResponse('يجب تحديد مسار الملف.', 400);
      const readRes = await callGitHub<GitHubContent>(`${base}/contents/${path}?ref=${branch}`, token);
      if (!readRes.ok) {
        await logOperation(db, { operation_type: 'read', file_path: path, branch_name: branch, status: 'failed', error_message: readRes.error, admin_id: user?.id ?? null });
        return errorResponse(readRes.error, readRes.status);
      }
      const content = readRes.data.encoding === 'base64' ? new TextDecoder().decode(Uint8Array.from(atob(readRes.data.content), (c) => c.charCodeAt(0))) : readRes.data.content;
      await logOperation(db, { operation_type: 'read', file_path: path, branch_name: branch, status: 'success', commit_sha: readRes.data.sha, admin_id: user?.id ?? null });
      return jsonResponse({ success: true, path, branch, sha: readRes.data.sha, content });
    }

    case 'create': {
      const path = payload.path?.trim();
      if (!path) return errorResponse('يجب تحديد مسار الملف.', 400);
      if (!payload.message?.trim()) return errorResponse('يجب إدخال رسالة Commit.', 400);
      if (payload.content === undefined) return errorResponse('يجب إدخال محتوى الملف.', 400);

      const createRes = await callGitHub<{ content: GitHubContent; commit: { sha: string } }>(`${base}/contents/${path}`, token, {
        method: 'PUT',
        body: JSON.stringify({
          message: payload.message,
          content: btoa(payload.content),
          branch,
        }),
      });
      if (!createRes.ok) {
        await logOperation(db, { operation_type: 'create', file_path: path, branch_name: branch, status: 'failed', error_message: createRes.error, admin_id: user?.id ?? null });
        return errorResponse(createRes.error, createRes.status);
      }
      await logOperation(db, { operation_type: 'create', file_path: path, branch_name: branch, status: 'success', commit_sha: createRes.data.commit?.sha || null, admin_id: user?.id ?? null });
      return jsonResponse({ success: true, path, branch, sha: createRes.data.content.sha, commit_sha: createRes.data.commit?.sha || null });
    }

    case 'update': {
      const path = payload.path?.trim();
      if (!path) return errorResponse('يجب تحديد مسار الملف.', 400);
      if (!payload.message?.trim()) return errorResponse('يجب إدخال رسالة Commit.', 400);
      if (payload.content === undefined) return errorResponse('يجب إدخال محتوى الملف.', 400);

      const readRes = await callGitHub<GitHubContent>(`${base}/contents/${path}?ref=${branch}`, token);
      if (!readRes.ok) {
        await logOperation(db, { operation_type: 'update', file_path: path, branch_name: branch, status: 'failed', error_message: readRes.error, admin_id: user?.id ?? null });
        return errorResponse(readRes.error, readRes.status);
      }

      const updateRes = await callGitHub<{ content: GitHubContent; commit: { sha: string } }>(`${base}/contents/${path}`, token, {
        method: 'PUT',
        body: JSON.stringify({
          message: payload.message,
          content: btoa(payload.content),
          sha: readRes.data.sha,
          branch,
        }),
      });
      if (!updateRes.ok) {
        await logOperation(db, { operation_type: 'update', file_path: path, branch_name: branch, status: 'failed', error_message: updateRes.error, admin_id: user?.id ?? null });
        return errorResponse(updateRes.error, updateRes.status);
      }
      await logOperation(db, { operation_type: 'update', file_path: path, branch_name: branch, status: 'success', commit_sha: updateRes.data.commit?.sha || null, admin_id: user?.id ?? null });
      return jsonResponse({ success: true, path, branch, sha: updateRes.data.content.sha, commit_sha: updateRes.data.commit?.sha || null });
    }

    default:
      return errorResponse('إجراء غير معروف.', 400);
  }
});
