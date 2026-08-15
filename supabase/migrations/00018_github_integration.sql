create table if not exists public.github_connection (
  id uuid primary key default gen_random_uuid(),
  repo_owner text not null,
  repo_name text not null,
  default_branch text not null default 'main',
  auth_type text not null default 'pat',
  connection_status text not null default 'disconnected',
  permissions jsonb not null default '[]'::jsonb,
  last_connected_at timestamptz null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

comment on table public.github_connection is 'Stores read-only metadata for the GitHub integration. The actual token is never saved here; it lives in an Edge Function secret.';

alter table public.github_connection enable row level security;

create table if not exists public.github_operations_log (
  id uuid primary key default gen_random_uuid(),
  operation_type text not null,
  file_path text null,
  branch_name text not null default 'main',
  status text not null,
  error_message text null,
  commit_sha text null,
  admin_id uuid null references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);

comment on table public.github_operations_log is 'Audit log for all GitHub integration operations. Never contains the GitHub token.';

alter table public.github_operations_log enable row level security;

-- Drop and recreate policies to ensure clean state.
do $$
begin
  -- github_connection policies
  drop policy if exists "admin select github_connection" on public.github_connection;
  drop policy if exists "admin insert github_connection" on public.github_connection;
  drop policy if exists "admin update github_connection" on public.github_connection;
  drop policy if exists "admin delete github_connection" on public.github_connection;
  drop policy if exists "anon no access github_connection" on public.github_connection;
  drop policy if exists "authenticated no access github_connection" on public.github_connection;

  create policy "admin select github_connection" on public.github_connection
    for select to authenticated using (auth.jwt() ->> 'role' = 'admin');
  create policy "admin insert github_connection" on public.github_connection
    for insert to authenticated with check (auth.jwt() ->> 'role' = 'admin');
  create policy "admin update github_connection" on public.github_connection
    for update to authenticated using (auth.jwt() ->> 'role' = 'admin') with check (auth.jwt() ->> 'role' = 'admin');
  create policy "admin delete github_connection" on public.github_connection
    for delete to authenticated using (auth.jwt() ->> 'role' = 'admin');
  create policy "anon no access github_connection" on public.github_connection
    for select to anon using (false);
  create policy "authenticated no access github_connection" on public.github_connection
    for select to authenticated using (auth.jwt() ->> 'role' = 'admin');

  -- github_operations_log policies
  drop policy if exists "admin select github_operations_log" on public.github_operations_log;
  drop policy if exists "admin insert github_operations_log" on public.github_operations_log;
  drop policy if exists "admin update github_operations_log" on public.github_operations_log;
  drop policy if exists "admin delete github_operations_log" on public.github_operations_log;
  drop policy if exists "anon no access github_operations_log" on public.github_operations_log;
  drop policy if exists "authenticated no access github_operations_log" on public.github_operations_log;

  create policy "admin select github_operations_log" on public.github_operations_log
    for select to authenticated using (auth.jwt() ->> 'role' = 'admin');
  create policy "admin insert github_operations_log" on public.github_operations_log
    for insert to authenticated with check (auth.jwt() ->> 'role' = 'admin');
  create policy "admin update github_operations_log" on public.github_operations_log
    for update to authenticated using (auth.jwt() ->> 'role' = 'admin') with check (auth.jwt() ->> 'role' = 'admin');
  create policy "admin delete github_operations_log" on public.github_operations_log
    for delete to authenticated using (auth.jwt() ->> 'role' = 'admin');
  create policy "anon no access github_operations_log" on public.github_operations_log
    for select to anon using (false);
  create policy "authenticated no access github_operations_log" on public.github_operations_log
    for select to authenticated using (auth.jwt() ->> 'role' = 'admin');
end
$$;

create or replace function public.update_github_connection_updated_at()
returns trigger as $$
begin
  new.updated_at = now();
  return new;
end;
$$ language plpgsql security definer;

drop trigger if exists github_connection_updated_at on public.github_connection;

create trigger github_connection_updated_at
  before update on public.github_connection
  for each row
  execute function public.update_github_connection_updated_at();

create unique index if not exists github_connection_single_row on public.github_connection ((true));

insert into public.github_connection (repo_owner, repo_name, default_branch, auth_type, connection_status, permissions)
values ('', '', 'main', 'pat', 'disconnected', '[]'::jsonb)
on conflict ((true)) do nothing;