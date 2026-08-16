import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

interface LoginPayload {
  email?: string;
  password?: string;
}

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  let payload: LoginPayload;
  try {
    payload = await req.json();
  } catch {
    return errorResponse('Invalid JSON', 400);
  }

  const email = payload.email?.trim().toLowerCase();
  const password = payload.password?.trim();
  if (!email || !password) {
    return errorResponse('email and password are required', 400);
  }

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  const { data, error } = await db.auth.signInWithPassword({ email, password });
  if (error || !data.session) {
    return errorResponse(error?.message ?? 'Invalid credentials', 401);
  }

  const session = data.session;
  const user = session.user;

  // Verify admin role
  const { data: profile, error: profileError } = await db
    .from('profiles')
    .select('role')
    .eq('id', user.id)
    .single();

  if (profileError || profile?.role !== 'admin') {
    return errorResponse('Unauthorized: admin role required', 403);
  }

  return jsonResponse({
    ok: true,
    access_token: session.access_token,
    refresh_token: session.refresh_token,
    expires_at: session.expires_at,
    token_type: session.token_type,
    user: {
      id: user.id,
      email: user.email,
      role: profile.role,
    },
  });
});
