create table if not exists public.sms_device_status (
  id uuid primary key default gen_random_uuid(),
  device_id text not null unique,
  device_model text,
  device_name text,
  app_version text,
  last_heartbeat_at timestamptz not null default now(),
  last_webhook_at timestamptz,
  status text not null default 'online',
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

comment on table public.sms_device_status is 'Tracks connected Android SMS reader devices and their online/offline status.';

alter table public.sms_device_status enable row level security;

-- Recreate policies cleanly
do $$
begin
  drop policy if exists "admin select sms_device_status" on public.sms_device_status;
  drop policy if exists "admin insert sms_device_status" on public.sms_device_status;
  drop policy if exists "admin update sms_device_status" on public.sms_device_status;
  drop policy if exists "admin delete sms_device_status" on public.sms_device_status;
  drop policy if exists "anon no access sms_device_status" on public.sms_device_status;
  drop policy if exists "authenticated no access sms_device_status" on public.sms_device_status;

  create policy "admin select sms_device_status" on public.sms_device_status
    for select to authenticated using (auth.jwt() ->> 'role' = 'admin');
  create policy "admin insert sms_device_status" on public.sms_device_status
    for insert to authenticated with check (auth.jwt() ->> 'role' = 'admin');
  create policy "admin update sms_device_status" on public.sms_device_status
    for update to authenticated using (auth.jwt() ->> 'role' = 'admin') with check (auth.jwt() ->> 'role' = 'admin');
  create policy "admin delete sms_device_status" on public.sms_device_status
    for delete to authenticated using (auth.jwt() ->> 'role' = 'admin');
  create policy "anon no access sms_device_status" on public.sms_device_status
    for select to anon using (false);
  create policy "authenticated no access sms_device_status" on public.sms_device_status
    for select to authenticated using (auth.jwt() ->> 'role' = 'admin');
end
$$;

-- Function to mark stale devices offline
create or replace function public.update_sms_device_statuses()
returns void as $$
begin
  update public.sms_device_status
  set status = 'offline'
  where status = 'online'
    and last_heartbeat_at < now() - interval '2 minutes';
end;
$$ language plpgsql security definer;

-- Trigger to update updated_at automatically
create or replace function public.update_sms_device_status_updated_at()
returns trigger as $$
begin
  new.updated_at = now();
  return new;
end;
$$ language plpgsql security definer;

drop trigger if exists sms_device_status_updated_at on public.sms_device_status;

create trigger sms_device_status_updated_at
  before update on public.sms_device_status
  for each row
  execute function public.update_sms_device_status_updated_at();
