import { createClient } from 'npm:@supabase/supabase-js@2';

/** Returns authenticated user's id + role, or throws. */
export async function requireAuth(req: Request): Promise<{ userId: string; role: string }> {
  const authHeader = req.headers.get('Authorization');
  if (!authHeader?.startsWith('Bearer ')) throw 'Unauthorized: missing token';

  const anonClient = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_ANON_KEY')!,
    { auth: { persistSession: false } }
  );
  const { data: { user }, error } = await anonClient.auth.getUser(
    authHeader.replace('Bearer ', '')
  );
  if (error || !user) throw 'Unauthorized: invalid token';

  const adminClient = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } }
  );
  const { data: profile } = await adminClient
    .from('profiles')
    .select('role')
    .eq('id', user.id)
    .maybeSingle();

  return { userId: user.id, role: profile?.role ?? 'user' };
}
