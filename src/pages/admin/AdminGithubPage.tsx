import React, { useCallback, useEffect, useState } from 'react';
import { Github, Loader2, CheckCircle2, XCircle, RefreshCw, FileText, GitBranch, Upload, Eye, Save, AlertCircle } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import { toast } from 'sonner';

interface ConnectionMeta {
  id: string;
  repo_owner: string;
  repo_name: string;
  default_branch: string;
  auth_type: string;
  connection_status: 'connected' | 'disconnected';
  permissions: string[];
  last_connected_at: string | null;
}

interface OperationLog {
  id: string;
  operation_type: string;
  file_path: string | null;
  branch_name: string;
  status: 'success' | 'failed';
  error_message: string | null;
  commit_sha: string | null;
  created_at: string;
}

interface TestResult {
  success: boolean;
  owner?: string;
  repo?: string;
  full_name?: string;
  default_branch?: string;
  private?: boolean;
  html_url?: string;
  permissions?: string[];
  token_masked?: string;
  error?: string;
}

export default function AdminGithubPage() {
  const [meta, setMeta] = useState<ConnectionMeta | null>(null);
  const [logs, setLogs] = useState<OperationLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [testing, setTesting] = useState(false);
  const [branches, setBranches] = useState<string[]>([]);
  const [currentBranch, setCurrentBranch] = useState('main');

  const [owner, setOwner] = useState('');
  const [repo, setRepo] = useState('');
  const [filePath, setFilePath] = useState('');
  const [fileContent, setFileContent] = useState('');
  const [commitMessage, setCommitMessage] = useState('');
  const [readContent, setReadContent] = useState('');
  const [readSha, setReadSha] = useState('');
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    const [{ data: metaRows }, { data: logRows }] = await Promise.all([
      supabase.from('github_connection').select('*').limit(1),
      supabase.from('github_operations_log').select('*').order('created_at', { ascending: false }).limit(50),
    ]);
    const m = (metaRows?.[0] ?? null) as ConnectionMeta | null;
    setMeta(m);
    setLogs((logRows ?? []) as OperationLog[]);
    if (m) {
      setOwner(m.repo_owner);
      setRepo(m.repo_name);
      setCurrentBranch(m.default_branch || 'main');
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const invoke = async (body: Record<string, unknown>) => {
    const { data, error } = await supabase.functions.invoke('github-integration', { body });
    if (error) {
      const errMsg = error?.context ? await (error.context as any).text?.().catch(() => error.message) : error?.message;
      throw new Error(errMsg);
    }
    if (!data?.success && data?.error) throw new Error(data.error);
    return data;
  };

  const handleTest = async () => {
    setTesting(true);
    try {
      const data = await invoke({ action: 'test', owner, repo, branch: currentBranch }) as TestResult;
      toast.success('تم الاتصال بـ GitHub بنجاح');
      await load();
      if (data.default_branch) setCurrentBranch(data.default_branch);
    } catch (err: any) {
      toast.error(err?.message || 'فشل الاتصال بـ GitHub');
    } finally {
      setTesting(false);
    }
  };

  const loadBranches = async () => {
    setActionLoading('branches');
    try {
      const data = await invoke({ action: 'branches', owner, repo, branch: currentBranch }) as { branches: { name: string; last_commit_sha: string }[] };
      setBranches(data.branches.map((b) => b.name));
      toast.success('تم تحميل الفروع');
    } catch (err: any) {
      toast.error(err?.message || 'فشل تحميل الفروع');
    } finally {
      setActionLoading(null);
    }
  };

  const handleRead = async () => {
    if (!filePath.trim()) { toast.error('أدخل مسار الملف'); return; }
    setActionLoading('read');
    try {
      const data = await invoke({ action: 'read', owner, repo, branch: currentBranch, path: filePath }) as { content: string; sha: string };
      setReadContent(data.content);
      setReadSha(data.sha);
      toast.success('تم قراءة الملف');
    } catch (err: any) {
      toast.error(err?.message || 'فشل قراءة الملف');
    } finally {
      setActionLoading(null);
    }
  };

  const handleCreate = async () => {
    if (!filePath.trim() || !commitMessage.trim() || fileContent === undefined) { toast.error('أدخل مسار الملف ورسالة Commit والمحتوى'); return; }
    setActionLoading('create');
    try {
      await invoke({ action: 'create', owner, repo, branch: currentBranch, path: filePath, content: fileContent, message: commitMessage });
      toast.success('تم إنشاء الملف بنجاح');
      await load();
    } catch (err: any) {
      toast.error(err?.message || 'فشل إنشاء الملف');
    } finally {
      setActionLoading(null);
    }
  };

  const handleUpdate = async () => {
    if (!filePath.trim() || !commitMessage.trim() || fileContent === undefined) { toast.error('أدخل مسار الملف ورسالة Commit والمحتوى'); return; }
    setActionLoading('update');
    try {
      await invoke({ action: 'update', owner, repo, branch: currentBranch, path: filePath, content: fileContent, message: commitMessage });
      toast.success('تم تحديث الملف بنجاح');
      await load();
    } catch (err: any) {
      toast.error(err?.message || 'فشل تحديث الملف');
    } finally {
      setActionLoading(null);
    }
  };

  return (
    <AdminLayout>
      <div className="px-4 md:px-6 py-6 space-y-6">
        <div className="flex items-center gap-3 flex-wrap">
          <div className="space-y-0.5 flex-1 min-w-0">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
              <Github className="w-5 h-5 text-primary" />
              تكامل GitHub
            </h1>
            <p className="text-sm text-muted-foreground">ربط المستودع الخارجي بشكل آمن وإدارة الملفات والفروع</p>
          </div>
          <Button variant="outline" size="sm" className="gap-1 shrink-0" onClick={load} disabled={loading}>
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} /> تحديث
          </Button>
        </div>

        <Tabs defaultValue="connection" className="w-full">
          <TabsList className="w-full justify-start overflow-x-auto">
            <TabsTrigger value="connection">إعدادات الاتصال</TabsTrigger>
            <TabsTrigger value="files">إدارة الملفات</TabsTrigger>
            <TabsTrigger value="branches">الفروع</TabsTrigger>
            <TabsTrigger value="logs">سجل العمليات</TabsTrigger>
          </TabsList>

          <TabsContent value="connection" className="space-y-4">
            <Card>
              <CardHeader>
                <CardTitle className="text-base">حالة الاتصال</CardTitle>
                <CardDescription>يتم تخزين التوكن في بيئة السيرفر فقط ولا يُعرض هنا.</CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="flex items-center gap-3">
                  {meta?.connection_status === 'connected' ? (
                    <Badge className="gap-1 bg-green-500/10 text-green-500 hover:bg-green-500/20"><CheckCircle2 className="w-3 h-3" /> متصل</Badge>
                  ) : (
                    <Badge variant="secondary" className="gap-1"><XCircle className="w-3 h-3" /> غير متصل</Badge>
                  )}
                  {meta?.last_connected_at && (
                    <span className="text-xs text-muted-foreground">آخر اتصال: {new Date(meta.last_connected_at).toLocaleString('ar-SA')}</span>
                  )}
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                  <div className="space-y-1.5">
                    <label className="text-sm font-medium">مالك المستودع</label>
                    <Input value={owner} onChange={(e) => setOwner(e.target.value)} placeholder="مثال: octocat" />
                  </div>
                  <div className="space-y-1.5">
                    <label className="text-sm font-medium">اسم المستودع</label>
                    <Input value={repo} onChange={(e) => setRepo(e.target.value)} placeholder="مثال: hello-world" />
                  </div>
                </div>

                <div className="space-y-1.5">
                  <label className="text-sm font-medium">الفرع الافتراضي</label>
                  <Input value={currentBranch} onChange={(e) => setCurrentBranch(e.target.value)} placeholder="main" />
                </div>

                <Button onClick={handleTest} disabled={testing || !owner || !repo} className="gap-1.5">
                  {testing ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />}
                  اختبار الاتصال
                </Button>

                {meta && meta.connection_status === 'connected' && (
                  <div className="p-3 rounded-lg bg-muted/30 border border-border space-y-2 text-sm">
                    <p><span className="text-muted-foreground">المستودع:</span> {meta.repo_owner}/{meta.repo_name}</p>
                    <p><span className="text-muted-foreground">الفرع الافتراضي:</span> {meta.default_branch}</p>
                    <p><span className="text-muted-foreground">الصلاحيات:</span> {meta.permissions?.join(', ') || '—'}</p>
                  </div>
                )}
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="files" className="space-y-4">
            <Card>
              <CardHeader>
                <CardTitle className="text-base flex items-center gap-2"><FileText className="w-4 h-4" /> إدارة الملفات</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="space-y-1.5">
                  <label className="text-sm font-medium">مسار الملف</label>
                  <Input value={filePath} onChange={(e) => setFilePath(e.target.value)} placeholder="src/pages/admin/AdminPage.tsx" />
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                  <Button variant="outline" className="gap-1" onClick={handleRead} disabled={actionLoading === 'read'}>
                    {actionLoading === 'read' ? <Loader2 className="w-4 h-4 animate-spin" /> : <Eye className="w-4 h-4" />}
                    قراءة الملف
                  </Button>
                  <Button variant="outline" className="gap-1" onClick={() => { setFileContent(readContent); setCommitMessage(''); }} disabled={!readContent}>
                    <Upload className="w-4 h-4" /> تحميل المحتوى للتعديل
                  </Button>
                </div>

                {readContent && (
                  <div className="space-y-1.5">
                    <label className="text-sm font-medium">محتوى الملف المقروء</label>
                    <Textarea value={readContent} readOnly rows={6} className="font-mono text-xs bg-muted/20" />
                    <p className="text-xs text-muted-foreground">SHA: {readSha}</p>
                  </div>
                )}

                <div className="space-y-1.5">
                  <label className="text-sm font-medium">محتوى الملف (للإنشاء/التعديل)</label>
                  <Textarea value={fileContent} onChange={(e) => setFileContent(e.target.value)} rows={8} placeholder="// أضف محتوى الملف هنا" className="font-mono text-xs" />
                </div>

                <div className="space-y-1.5">
                  <label className="text-sm font-medium">رسالة Commit</label>
                  <Input value={commitMessage} onChange={(e) => setCommitMessage(e.target.value)} placeholder="إضافة/تحديث الملف..." />
                </div>

                <div className="flex items-center gap-2 flex-wrap">
                  <Button className="gap-1" onClick={handleCreate} disabled={actionLoading === 'create'}>
                    {actionLoading === 'create' ? <Loader2 className="w-4 h-4 animate-spin" /> : <Upload className="w-4 h-4" />}
                    إنشاء ملف
                  </Button>
                  <Button variant="secondary" className="gap-1" onClick={handleUpdate} disabled={actionLoading === 'update'}>
                    {actionLoading === 'update' ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                    تحديث ملف
                  </Button>
                </div>
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="branches" className="space-y-4">
            <Card>
              <CardHeader>
                <CardTitle className="text-base flex items-center gap-2"><GitBranch className="w-4 h-4" /> الفروع</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <Button className="gap-1" onClick={loadBranches} disabled={actionLoading === 'branches' || !owner || !repo}>
                  {actionLoading === 'branches' ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
                  تحميل الفروع
                </Button>
                {branches.length > 0 && (
                  <div className="space-y-1.5">
                    <label className="text-sm font-medium">الفرع الحالي</label>
                    <Select value={currentBranch} onValueChange={setCurrentBranch}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {branches.map((b) => <SelectItem key={b} value={b}>{b}</SelectItem>)}
                      </SelectContent>
                    </Select>
                  </div>
                )}
                {branches.length === 0 && !actionLoading && (
                  <p className="text-sm text-muted-foreground flex items-center gap-2"><AlertCircle className="w-4 h-4" /> اضغط تحميل الفروع أولاً.</p>
                )}
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="logs" className="space-y-4">
            <Card>
              <CardHeader>
                <CardTitle className="text-base">سجل العمليات</CardTitle>
              </CardHeader>
              <CardContent className="p-0">
                {logs.length === 0 ? (
                  <p className="text-sm text-muted-foreground text-center py-8">لا توجد عمليات مسجلة.</p>
                ) : (
                  <div className="divide-y divide-border">
                    {logs.map((log) => (
                      <div key={log.id} className="p-3 flex items-start justify-between gap-3">
                        <div className="min-w-0 space-y-1">
                          <div className="flex items-center gap-2">
                            <Badge variant={log.status === 'success' ? 'default' : 'destructive'} className="text-xs">
                              {log.status === 'success' ? 'نجاح' : 'فشل'}
                            </Badge>
                            <span className="text-xs font-medium">{log.operation_type}</span>
                          </div>
                          {log.file_path && <p className="text-xs text-muted-foreground truncate">{log.file_path}</p>}
                          {log.error_message && <p className="text-xs text-destructive truncate">{log.error_message}</p>}
                          {log.commit_sha && <p className="text-xs text-muted-foreground font-mono truncate">{log.commit_sha.slice(0, 7)}</p>}
                        </div>
                        <span className="text-xs text-muted-foreground shrink-0">{new Date(log.created_at).toLocaleString('ar-SA')}</span>
                      </div>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      </div>
    </AdminLayout>
  );
}
