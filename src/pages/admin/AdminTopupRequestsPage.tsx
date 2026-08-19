import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Wallet, Loader2, CheckCircle2, XCircle, ArrowLeft, RefreshCw,
  Phone, Zap, User, Hash, Filter, Smartphone, ScanLine,
  AlertTriangle, Clock, ShieldAlert, RotateCcw, Search,
  FolderOpen, WifiOff, Wifi, DollarSign, CreditCard,
  Calendar, ChevronDown, ChevronUp, Copy, ReceiptText,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Separator } from '@/components/ui/separator';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import type { WalletTopupRequest, Profile } from '@/types/types';
import { toast } from 'sonner';

// ─────────────────────────────────────────────────────────────────────────────
// Status config
// ─────────────────────────────────────────────────────────────────────────────
interface StatusConfig {
  label: string;
  textCls: string;
  bgCls: string;
  borderCls: string;
  dot: string;
  pulse?: boolean;
}

const S: Record<string, StatusConfig> = {
  pending:                  { label:'قيد الانتظار',             textCls:'text-amber-500',   bgCls:'bg-amber-500/8',   borderCls:'border-amber-400/30',   dot:'#F59E0B' },
  scanning:                 { label:'جاري الفحص',               textCls:'text-blue-500',    bgCls:'bg-blue-500/8',    borderCls:'border-blue-400/30',    dot:'#3B82F6', pulse:true },
  confirmed:                { label:'تم التأكيد',                textCls:'text-green-600',   bgCls:'bg-green-500/8',   borderCls:'border-green-500/30',   dot:'#059669' },
  approved:                 { label:'تمت الموافقة',              textCls:'text-green-600',   bgCls:'bg-green-500/8',   borderCls:'border-green-500/30',   dot:'#059669' },
  failed:                   { label:'فشل',                       textCls:'text-red-500',     bgCls:'bg-red-500/8',     borderCls:'border-red-400/30',     dot:'#EF4444' },
  expired:                  { label:'انتهت الصلاحية',            textCls:'text-slate-400',   bgCls:'bg-muted/30',      borderCls:'border-border',         dot:'#94A3B8' },
  reopened:                 { label:'أُعيد فتحه',                textCls:'text-violet-500',  bgCls:'bg-violet-500/8',  borderCls:'border-violet-400/30',  dot:'#8B5CF6' },
  cancelled:                { label:'ملغي',                      textCls:'text-slate-400',   bgCls:'bg-muted/20',      borderCls:'border-border',         dot:'#94A3B8' },
  rejected:                 { label:'مرفوض',                     textCls:'text-red-500',     bgCls:'bg-red-500/8',     borderCls:'border-red-400/30',     dot:'#EF4444' },
  admin_offline:            { label:'الجهاز غير متصل',           textCls:'text-orange-500',  bgCls:'bg-orange-500/8',  borderCls:'border-orange-400/30',  dot:'#F97316' },
  waiting_for_verification: { label:'ينتظر الجهاز',              textCls:'text-amber-400',   bgCls:'bg-amber-500/5',   borderCls:'border-amber-400/25',   dot:'#FBBF24', pulse:true },
  matched:                  { label:'تم العثور على تطابق',       textCls:'text-green-500',   bgCls:'bg-green-500/8',   borderCls:'border-green-400/30',   dot:'#10B981' },
  no_match:                 { label:'لا يوجد تطابق',             textCls:'text-red-400',     bgCls:'bg-red-500/8',     borderCls:'border-red-400/30',     dot:'#F87171' },
  retrying:                 { label:'إعادة المحاولة',             textCls:'text-blue-400',    bgCls:'bg-blue-500/8',    borderCls:'border-blue-400/30',    dot:'#60A5FA', pulse:true },
  completed:                { label:'مكتمل',                     textCls:'text-green-700',   bgCls:'bg-green-600/8',   borderCls:'border-green-600/30',   dot:'#047857' },
  not_found:                { label:'لم يُعثر عليه',             textCls:'text-slate-400',   bgCls:'bg-muted/20',      borderCls:'border-border',         dot:'#94A3B8' },
  amount_mismatch:          { label:'مبلغ غير مطابق',            textCls:'text-orange-500',  bgCls:'bg-orange-500/8',  borderCls:'border-orange-400/30',  dot:'#F97316' },
  duplicate:                { label:'مكرر',                      textCls:'text-purple-500',  bgCls:'bg-purple-500/8',  borderCls:'border-purple-400/30',  dot:'#A855F7' },
  manual_review:            { label:'مراجعة يدوية',              textCls:'text-amber-500',   bgCls:'bg-amber-500/8',   borderCls:'border-amber-400/30',   dot:'#F59E0B' },
};

function resolveStatus(order_status: string, ver_status?: string, scan_status?: string): StatusConfig {
  if (['confirmed','approved'].includes(order_status)) return S['confirmed'];
  if (['rejected','cancelled','duplicate'].includes(order_status)) return S[order_status] ?? S['rejected'];
  if (order_status === 'expired')  return S['expired'];
  if (order_status === 'reopened') return S['reopened'];
  if (order_status === 'failed')   return S['failed'];
  if (scan_status === 'duplicate') return S['duplicate'];
  if (scan_status === 'amount_mismatch') return S['amount_mismatch'];
  if (ver_status && S[ver_status]) return S[ver_status];
  if (scan_status && S[scan_status]) return S[scan_status];
  return S[order_status] ?? { label: order_status, textCls:'text-muted-foreground', bgCls:'', borderCls:'border-border', dot:'#94A3B8' };
}

// ─────────────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────────────
interface DashboardStats {
  scanning: number; confirmed: number; failed: number; expired: number;
  admin_offline_count: number; reopened: number; total: number;
  device_online: boolean; last_heartbeat_at: string | null; pending_queue: number;
}
type FilterStatus = 'all'|'review'|'pending'|'scanning'|'approved'|'rejected'|'expired'|'reopened';

// ─────────────────────────────────────────────────────────────────────────────
// Open-case dialog
// ─────────────────────────────────────────────────────────────────────────────
function CaseDialog({ open, onClose, onConfirm, orderNum }: {
  open: boolean; onClose: ()=>void;
  onConfirm: (reason:string, notes:string)=>void;
  orderNum?: number|null;
}) {
  const [reason, setReason] = useState('');
  const [notes,  setNotes]  = useState('');
  useEffect(() => { if (!open) { setReason(''); setNotes(''); } }, [open]);
  return (
    <Dialog open={open} onOpenChange={v => !v && onClose()}>
      <DialogContent className="max-w-[calc(100%-2rem)] md:max-w-lg">
        <DialogHeader>
          <DialogTitle>فتح قضية دعم {orderNum ? `— الطلب #${orderNum}` : ''}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3 pt-1">
          <div className="space-y-1.5">
            <Label>سبب القضية *</Label>
            <Textarea rows={2} placeholder="مثال: العميل أفاد بإتمام التحويل لكن الفحص لم يجد الرسالة"
              value={reason} onChange={e => setReason(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <Label>ملاحظات (اختياري)</Label>
            <Textarea rows={2} placeholder="تفاصيل إضافية…" value={notes} onChange={e => setNotes(e.target.value)} />
          </div>
        </div>
        <DialogFooter className="gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>إلغاء</Button>
          <Button disabled={!reason.trim()} onClick={() => onConfirm(reason.trim(), notes.trim())}>
            <FolderOpen className="w-4 h-4 mr-1.5" /> فتح القضية
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Info row
// ─────────────────────────────────────────────────────────────────────────────
function InfoRow({ label, value, ltr, mono, cls, suffix, onCopy }: {
  label:string; value?:string|null; ltr?:boolean; mono?:boolean;
  cls?:string; suffix?:React.ReactNode; onCopy?:()=>void;
}) {
  if (!value) return null;
  return (
    <div className="flex items-start justify-between gap-2 min-w-0 text-sm">
      <span className="text-muted-foreground shrink-0 text-xs">{label}:</span>
      <div className="flex items-center gap-1 min-w-0">
        <span dir={ltr ? 'ltr' : 'rtl'}
          className={`truncate ${mono ? 'font-mono text-xs' : ''} ${cls ?? 'text-foreground'}`}>
          {value}
        </span>
        {suffix}
        {onCopy && (
          <button onClick={onCopy} className="ml-1 shrink-0 text-muted-foreground hover:text-foreground transition-colors">
            <Copy className="w-3 h-3" />
          </button>
        )}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Main page
// ─────────────────────────────────────────────────────────────────────────────
export default function AdminTopupRequestsPage() {
  const navigate = useNavigate();
  const [requests,    setRequests]    = useState<WalletTopupRequest[]>([]);
  const [customers,   setCustomers]   = useState<Record<string, Profile>>({});
  const [loading,     setLoading]     = useState(true);
  const [processing,  setProcessing]  = useState<string | null>(null);
  const [filter,      setFilter]      = useState<FilterStatus>('review');
  const [stats,       setStats]       = useState<DashboardStats | null>(null);
  const [expanded,    setExpanded]    = useState<Set<string>>(new Set());
  const [caseTarget,  setCaseTarget]  = useState<WalletTopupRequest | null>(null);
  const channelRef = useRef<ReturnType<typeof supabase.channel> | null>(null);

  // ── Stats from backend RPC ─────────────────────────────────────────
  const loadStats = useCallback(async () => {
    const { data } = await supabase.rpc('get_topup_dashboard_stats');
    if (data) setStats(data as DashboardStats);
  }, []);

  // ── Load list ──────────────────────────────────────────────────────
  const load = useCallback(async () => {
    setLoading(true);
    let q = supabase
      .from('wallet_topup_requests')
      .select('*, profiles!customer_id(id, email, full_name, phone, wallet_balance, credits_balance)')
      .order('created_at', { ascending: false })
      .limit(120);
    if (filter === 'review')   q = q.in('status', ['pending','scanning']);
    else if (filter === 'scanning') q = q.eq('scan_status','scanning');
    else if (filter !== 'all') q = q.eq('status', filter);

    const { data } = await q;
    const rows = (data ?? []) as unknown as WalletTopupRequest[];
    setRequests(rows);
    const map: Record<string, Profile> = {};
    (data ?? []).forEach((r: any) => { if (r.profiles) map[r.profiles.id] = r.profiles as Profile; });
    setCustomers(map);
    setLoading(false);
    loadStats();
  }, [filter, loadStats]);

  // ── Realtime ───────────────────────────────────────────────────────
  useEffect(() => {
    load();
    const ch = supabase.channel(`admin-topup-${Date.now()}`)
      .on('postgres_changes', { event:'*', schema:'public', table:'wallet_topup_requests' }, p => {
        if (p.eventType === 'INSERT') {
          const r = p.new as WalletTopupRequest;
          const ss = (r as any).scan_status as string|undefined;
          const match = filter==='all'
            || (filter==='review' && ['pending','scanning'].includes(r.status))
            || (filter==='scanning' && ss==='scanning')
            || (!['review','scanning'].includes(filter) && r.status===filter);
          if (match) { setRequests(prev => [r,...prev]); toast.info('📥 طلب شحن جديد وصل!'); }
          loadStats();
        } else if (p.eventType === 'UPDATE') {
          const u = p.new as WalletTopupRequest;
          setRequests(prev => prev.map(r => r.id===u.id ? {...r,...u} : r));
          if (['approved','confirmed'].includes(u.status)) toast.success('✅ تم تأكيد طلب شحن');
          if ((u as any).verification_status === 'admin_offline') toast.warning('⚠️ جهاز التأكيد غير متصل');
          loadStats();
        }
      })
      .on('postgres_changes', { event:'*', schema:'public', table:'payment_orders' }, () => loadStats())
      .subscribe();
    channelRef.current = ch;
    return () => { supabase.removeChannel(ch).catch(()=>{}); };
  }, [load, filter, loadStats]);

  const toggleExpand = (id: string) =>
    setExpanded(prev => { const n=new Set(prev); n.has(id)?n.delete(id):n.add(id); return n; });

  const copy = (text: string, label: string) =>
    navigator.clipboard.writeText(text).then(() => toast.success(`✓ تم نسخ ${label}`));

  // ── Actions ────────────────────────────────────────────────────────
  const handleManualConfirm = async (r: WalletTopupRequest) => {
    const orderId = (r as any).payment_order_id as string|undefined;
    if (orderId) {
      if (!confirm(`تأكيد يدوي للطلب #${r.order_number}؟\nسيضاف ${r.credits_requested??1} Credit للعميل.`)) return;
      setProcessing(r.id);
      try {
        const { data, error } = await supabase.functions.invoke('admin-manual-confirm', {
          body: { order_id: orderId, reason: 'تأكيد يدوي من لوحة الأدمن', topup_request_id: r.id },
        });
        if (error || !data?.ok) { toast.error(data?.reason ?? error?.message ?? 'فشل التأكيد'); return; }
        toast.success(`✅ تم التأكيد — ${r.credits_requested??1} Credit أُضيفت`);
        load();
      } finally { setProcessing(null); }
    } else {
      // legacy fallback — direct wallet topup
      const credits = r.credits_requested ?? 1;
      if (!confirm(`تأكيد إضافة ${credits} Credit؟ (المبلغ: ${r.amount} جنيه)`)) return;
      setProcessing(r.id);
      try {
        const { error } = await supabase.functions.invoke('admin-wallet-topup', {
          body: { customer_id:r.customer_id, type:'credit', amount:credits,
            reason:`شحن يدوي - ${credits} credit - ${(r as any).sender_phone??''}` },
        });
        if (error) { toast.error('فشل إضافة الرصيد: '+error.message); return; }
        await supabase.from('wallet_topup_requests').update({
          status:'approved', processed_at: new Date().toISOString(),
          matched_automatically:false, notes:'موافقة يدوية من الأدمن',
        }).eq('id', r.id);
        toast.success(`✓ تمت الموافقة على شحن ${credits} Credit`);
      } finally { setProcessing(null); }
    }
  };

  const handleReject = async (r: WalletTopupRequest) => {
    if (!confirm('هل تريد رفض هذا الطلب؟')) return;
    setProcessing(r.id);
    await supabase.from('wallet_topup_requests').update({
      status:'rejected', processed_at:new Date().toISOString(), notes:'مرفوض من الأدمن',
    }).eq('id', r.id);
    toast.info('تم رفض الطلب');
    setProcessing(null);
  };

  const handleRescan = async (r: WalletTopupRequest) => {
    if (r.status==='approved') { toast.info('الطلب مكتمل بالفعل'); return; }
    if (!confirm(`إعادة فحص الطلب #${r.order_number ?? r.id.slice(0,8)}؟`)) return;
    setProcessing(r.id);
    try {
      const { data:{ user } } = await supabase.auth.getUser();
      const { data, error } = await supabase.rpc('admin_rescan_topup_request', {
        p_admin_id:user?.id, p_request_id:r.id, p_reason:'إعادة فحص يدوي من المسؤول',
      });
      if (error) { toast.error('فشل إعادة الفحص: '+error.message); return; }
      if (!data?.ok) { toast.error(data?.reason ?? 'فشل'); return; }
      toast.success('✓ تم إرسال طلب إعادة الفحص للجهاز');
    } finally { setProcessing(null); }
  };

  const handleReopen = async (r: WalletTopupRequest) => {
    const orderId = (r as any).payment_order_id as string|undefined;
    if (!orderId) { toast.error('لا يوجد payment_order_id مرتبط بهذا الطلب'); return; }
    if (!confirm(`إعادة فتح الطلب #${r.order_number}؟ سيتم إرساله للجهاز مجدداً`)) return;
    setProcessing(r.id);
    try {
      const { data, error } = await supabase.functions.invoke('admin-reopen-order', {
        body: { order_id:orderId, reason:'إعادة فتح يدوي من الأدمن' },
      });
      if (error || !data?.ok) { toast.error(data?.reason ?? error?.message ?? 'فشلت إعادة الفتح'); return; }
      toast.success('✅ تم إعادة فتح الطلب وإرساله للجهاز');
      load();
    } finally { setProcessing(null); }
  };

  const handleOpenCase = async (reason: string, notes: string) => {
    const r = caseTarget; if (!r) return;
    const orderId = (r as any).payment_order_id as string|undefined;
    if (!orderId) { toast.error('لا يوجد payment_order_id'); setCaseTarget(null); return; }
    setProcessing(r.id);
    try {
      const { data, error } = await supabase.functions.invoke('admin-open-case', {
        body: { order_id:orderId, reason, notes: notes||undefined },
      });
      if (error || !data?.ok) {
        toast.error(data?.reason==='case_already_open'
          ? 'قضية مفتوحة بالفعل لهذا الطلب'
          : data?.reason ?? error?.message ?? 'فشل فتح القضية');
        return;
      }
      toast.success('✅ تم فتح القضية بنجاح');
    } finally { setProcessing(null); setCaseTarget(null); }
  };

  const deviceOnline = stats?.device_online ?? false;

  // ── Render ─────────────────────────────────────────────────────────
  return (
    <AdminLayout>
      <div className="px-4 md:px-6 py-6 space-y-5">

        {/* Header */}
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate('/admin/wallet')}>
            <ArrowLeft className="w-4 h-4" />
          </Button>
          <div className="flex-1 min-w-0">
            <h1 className="text-xl font-bold flex items-center gap-2">
              <Wallet className="w-5 h-5 text-primary" />
              طلبات شحن الرصيد
            </h1>
            <p className="text-xs text-muted-foreground">يتحدث تلقائياً · بدون refresh</p>
          </div>
          <Button variant="outline" size="sm" className="gap-1.5 shrink-0" onClick={load} disabled={loading}>
            <RefreshCw className={`w-3.5 h-3.5 ${loading?'animate-spin':''}`} />
            تحديث
          </Button>
        </div>

        {/* Device status banner */}
        <div className={`flex items-center justify-between gap-3 px-4 py-3 rounded-xl border text-sm
          ${deviceOnline
            ? 'bg-green-500/8 border-green-500/25'
            : 'bg-orange-500/8 border-orange-500/25'}`}>
          <div className="flex items-center gap-2">
            {deviceOnline
              ? <Wifi className="w-4 h-4 text-green-500 shrink-0" />
              : <WifiOff className="w-4 h-4 text-orange-500 shrink-0" />}
            <span className={`font-semibold ${deviceOnline ? 'text-green-600' : 'text-orange-600'}`}>
              {deviceOnline ? 'نظام التأكيد متصل' : 'نظام التأكيد غير متصل'}
            </span>
          </div>
          <div className="text-right text-xs text-muted-foreground shrink-0 space-y-0.5">
            {!deviceOnline && (stats?.pending_queue ?? 0) > 0 && (
              <p className="text-orange-500 font-medium">{stats!.pending_queue} طلب ينتظر الفحص</p>
            )}
            {stats?.last_heartbeat_at && (
              <p>آخر اتصال: {new Date(stats.last_heartbeat_at).toLocaleTimeString('ar-EG')}</p>
            )}
          </div>
        </div>

        {/* Dashboard stats */}
        <div className="grid grid-cols-3 md:grid-cols-7 gap-2">
          {[
            { label:'قيد الفحص',     v: stats?.scanning          ?? '…', cls:'text-blue-500' },
            { label:'تم التأكيد',    v: stats?.confirmed         ?? '…', cls:'text-green-500' },
            { label:'فشل',           v: stats?.failed            ?? '…', cls:'text-red-500' },
            { label:'منتهي',         v: stats?.expired           ?? '…', cls:'text-slate-400' },
            { label:'ينتظر الاتصال', v: stats?.admin_offline_count ?? '…', cls:'text-orange-500' },
            { label:'أُعيد فتحه',    v: stats?.reopened          ?? '…', cls:'text-violet-500' },
            { label:'الكل (48h)',    v: stats?.total             ?? '…', cls:'text-foreground' },
          ].map(({ label, v, cls }) => (
            <Card key={label} className="p-2 text-center">
              <p className={`text-xl font-bold ${cls}`}>{v}</p>
              <p className="text-[10px] text-muted-foreground leading-tight mt-0.5">{label}</p>
            </Card>
          ))}
        </div>

        {/* Filter */}
        <div className="flex items-center gap-2">
          <Filter className="w-4 h-4 text-muted-foreground shrink-0" />
          <Select value={filter} onValueChange={v => setFilter(v as FilterStatus)}>
            <SelectTrigger className="w-48">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="review">قيد المراجعة</SelectItem>
              <SelectItem value="all">جميع الطلبات</SelectItem>
              <SelectItem value="pending">معلق</SelectItem>
              <SelectItem value="scanning">جاري الفحص</SelectItem>
              <SelectItem value="approved">تمت الموافقة</SelectItem>
              <SelectItem value="rejected">مرفوض</SelectItem>
              <SelectItem value="expired">منتهي الصلاحية</SelectItem>
              <SelectItem value="reopened">أُعيد فتحه</SelectItem>
            </SelectContent>
          </Select>
          <span className="text-xs text-muted-foreground">{requests.length} طلب</span>
        </div>

        {/* Cards list */}
        {loading ? (
          <div className="flex justify-center py-12">
            <Loader2 className="w-5 h-5 animate-spin text-muted-foreground" />
          </div>
        ) : requests.length === 0 ? (
          <div className="text-center py-14 text-sm text-muted-foreground">لا توجد طلبات</div>
        ) : (
          <div className="space-y-3">
            {requests.map(r => {
              const scanStatus = (r as any).scan_status as string|undefined;
              const verStatus  = (r as any).verification_status as string|undefined;
              const txId       = r.transaction_id;
              const auto       = r.matched_automatically;
              const failReason = r.failure_reason;
              const orderId    = (r as any).payment_order_id as string|undefined;
              const expiresAt  = (r as any).expires_at as string|undefined;
              const scanAttempt = (r as any).scan_attempt as number|undefined;
              const maxAttempts = (r as any).max_attempts as number|undefined;
              const amountFound = (r as any).amount_found as number|undefined;
              const c   = customers[r.customer_id];
              const cfg = resolveStatus(r.status, verStatus, scanStatus);
              const isExpanded   = expanded.has(r.id);
              const isProcessing = processing === r.id;
              const isDuplicate  = scanStatus === 'duplicate';
              const isAdminOffline = verStatus === 'admin_offline' || scanStatus === 'admin_offline';
              const isExpired = r.status === 'expired' || (r.status as string) === 'expired';
              const isTerminal = ['approved','confirmed','cancelled','duplicate'].includes(r.status as string);
              const canManualConfirm = !isTerminal && !isDuplicate;
              const canReject  = !isTerminal && !isDuplicate;
              const canRescan  = ['not_found','amount_mismatch','failed','no_match'].includes(scanStatus ?? '');
              const canReopen  = ['expired','failed','rejected'].includes(r.status) && !!orderId;
              const canCase    = !!orderId && !['approved','confirmed','duplicate'].includes(r.status);

              return (
                <div key={r.id}
                  className={`rounded-2xl border ${cfg.borderCls} ${cfg.bgCls} overflow-hidden transition-all`}>

                  {/* ── Card header ── */}
                  <div className="px-4 pt-4 pb-3 flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-base font-bold text-foreground">
                          {r.order_number ? `طلب شحن #${r.order_number}` : `طلب #${r.id.slice(0,8)}`}
                        </span>
                        {auto && (
                          <Badge className="text-[10px] px-1.5 bg-green-500/15 text-green-600 gap-0.5 border-0">
                            <Zap className="w-2.5 h-2.5" />تلقائي
                          </Badge>
                        )}
                      </div>
                      <p className="text-[11px] text-muted-foreground font-mono mt-0.5 truncate max-w-[14rem]">
                        {r.id}
                      </p>
                    </div>
                    {/* Status pill */}
                    <div className="flex flex-col items-end gap-1.5 shrink-0">
                      <span className={`inline-flex items-center gap-1.5 text-xs font-semibold
                        px-2.5 py-1 rounded-full border ${cfg.borderCls} ${cfg.textCls}`}>
                        <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${cfg.pulse ? 'animate-pulse' : ''}`}
                          style={{ backgroundColor: cfg.dot }} />
                        {cfg.label}
                      </span>
                    </div>
                  </div>

                  <Separator className="opacity-30" />

                  {/* ── Amount + Credits ── */}
                  <div className="px-4 py-3 grid grid-cols-2 gap-3">
                    <div>
                      <p className="text-[10px] text-muted-foreground mb-0.5 flex items-center gap-1">
                        <DollarSign className="w-3 h-3" />المبلغ المطلوب
                      </p>
                      <p className="text-2xl font-bold text-primary leading-tight" dir="ltr">
                        {(r.fingerprint_amount ?? r.amount).toFixed(2)}
                        <span className="text-sm font-normal text-muted-foreground ml-1">جنيه</span>
                      </p>
                    </div>
                    <div>
                      <p className="text-[10px] text-muted-foreground mb-0.5 flex items-center gap-1">
                        <CreditCard className="w-3 h-3" />الكريدت
                      </p>
                      <p className="text-2xl font-bold text-foreground leading-tight" dir="ltr">
                        {r.credits_requested ?? r.amount}
                        <span className="text-sm font-normal text-muted-foreground ml-1">Credit</span>
                      </p>
                    </div>
                  </div>

                  <Separator className="opacity-30" />

                  {/* ── Customer ── */}
                  <div className="px-4 py-3">
                    <p className="text-[10px] text-muted-foreground mb-1.5 flex items-center gap-1 font-medium">
                      <User className="w-3 h-3" />العميل
                    </p>
                    <div className="flex items-center justify-between gap-2 min-w-0">
                      <div className="min-w-0">
                        <p className="text-sm font-semibold text-foreground truncate">
                          {(c as any)?.full_name ?? c?.email?.split('@')[0] ?? '—'}
                        </p>
                        <p className="text-xs text-muted-foreground truncate" dir="ltr">
                          {c?.email ?? r.customer_id.slice(0,20)+'…'}
                        </p>
                      </div>
                      {c?.email && (
                        <button onClick={() => copy(c.email!, 'الإيميل')}
                          className="text-muted-foreground hover:text-foreground transition-colors shrink-0">
                          <Copy className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  </div>

                  {/* ── Admin-offline warning ── */}
                  {isAdminOffline && (
                    <div className="mx-4 mb-3 p-3 rounded-xl bg-orange-500/10 border border-orange-400/25 text-xs text-orange-600 space-y-1">
                      <p className="font-semibold flex items-center gap-1.5">
                        <WifiOff className="w-3.5 h-3.5 shrink-0" />
                        نظام التأكيد التلقائي غير متصل حاليًا
                      </p>
                      <p className="text-orange-500/80">
                        تم تسجيل الطلب. سيُفحص عند عودة الجهاز. لا تطلب من العميل إعادة التحويل.
                      </p>
                    </div>
                  )}

                  {/* ── Expand toggle ── */}
                  <button
                    onClick={() => toggleExpand(r.id)}
                    className="w-full flex items-center justify-center gap-1 py-2 text-xs text-muted-foreground hover:text-foreground transition-colors">
                    {isExpanded
                      ? <><ChevronUp className="w-3.5 h-3.5" />إخفاء التفاصيل</>
                      : <><ChevronDown className="w-3.5 h-3.5" />عرض التفاصيل</>}
                  </button>

                  {/* ── Expanded details ── */}
                  {isExpanded && (
                    <div className="px-4 pb-3 space-y-3">
                      <Separator className="opacity-30" />

                      {/* Transfer data */}
                      <div>
                        <p className="text-[10px] text-muted-foreground font-medium mb-2 flex items-center gap-1">
                          <Phone className="w-3 h-3" />بيانات التحويل
                        </p>
                        <div className="space-y-1.5">
                          <InfoRow label="رقم المحوّل"   value={r.sender_phone} ltr
                            onCopy={r.sender_phone ? ()=>copy(r.sender_phone!, 'رقم المحوّل') : undefined} />
                          <InfoRow label="اسم صاحب المحفظة" value={r.sender_name} />
                          {amountFound != null && (
                            <InfoRow label="المبلغ المستلَم"
                              value={`${amountFound.toFixed(2)} جنيه`}
                              cls={amountFound !== (r.fingerprint_amount ?? r.amount)
                                ? 'text-orange-500 font-semibold' : 'text-green-500 font-semibold'} />
                          )}
                          {txId && (
                            <InfoRow label="رقم العملية" value={txId} ltr mono
                              onCopy={() => copy(txId, 'رقم العملية')}
                              suffix={isDuplicate ? <span className="text-purple-500 text-xs">(مكرر)</span> : undefined} />
                          )}
                          <InfoRow label="طريقة الدفع"
                            value={r.payment_method === 'vodafone_cash' ? 'فودافون كاش'
                              : r.payment_method ?? undefined} />
                        </div>
                      </div>

                      <Separator className="opacity-30" />

                      {/* Timestamps */}
                      <div>
                        <p className="text-[10px] text-muted-foreground font-medium mb-2 flex items-center gap-1">
                          <Calendar className="w-3 h-3" />تفاصيل الطلب
                        </p>
                        <div className="space-y-1.5">
                          <InfoRow label="تم الإنشاء"
                            value={new Date(r.created_at).toLocaleString('ar-EG', { hour12:true })} />
                          {expiresAt && (
                            <InfoRow label="ينتهي في"
                              value={new Date(expiresAt).toLocaleString('ar-EG', { hour12:true })}
                              cls={new Date(expiresAt) < new Date() ? 'text-red-500' : 'text-foreground'} />
                          )}
                          {r.assigned_device_id && (
                            <InfoRow label="جهاز الفحص" value={r.assigned_device_id.slice(0,18)+'…'} mono />
                          )}
                          {r.scanning_started_at && (
                            <InfoRow label="بدأ الفحص"
                              value={new Date(r.scanning_started_at).toLocaleString('ar-EG', { hour12:true })} />
                          )}
                        </div>
                      </div>

                      {/* Scan progress */}
                      {(scanAttempt != null || scanStatus) && (
                        <>
                          <Separator className="opacity-30" />
                          <div>
                            <p className="text-[10px] text-muted-foreground font-medium mb-2 flex items-center gap-1">
                              <ScanLine className="w-3 h-3" />حالة الفحص
                            </p>
                            <div className="space-y-1.5">
                              {scanAttempt != null && (
                                <InfoRow label="المحاولة" value={`${scanAttempt} / ${maxAttempts ?? 3}`} />
                              )}
                              {verStatus && (
                                <InfoRow label="حالة التحقق" value={S[verStatus]?.label ?? verStatus} />
                              )}
                            </div>
                          </div>
                        </>
                      )}

                      {/* Failure reason */}
                      {failReason && (
                        <div className="p-2.5 rounded-lg bg-destructive/5 border border-destructive/20
                          text-xs text-destructive flex items-start gap-1.5">
                          <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                          {failReason}
                        </div>
                      )}
                    </div>
                  )}

                  {/* ── Action buttons ── */}
                  <div className="px-4 pb-4 space-y-2">
                    <Separator className="opacity-30 mb-3" />

                    {/* Manual confirm + reject */}
                    {canManualConfirm && (
                      <div className="grid grid-cols-2 gap-2">
                        <Button size="sm" className="gap-1.5"
                          onClick={() => handleManualConfirm(r)} disabled={isProcessing}>
                          {isProcessing
                            ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                            : <CheckCircle2 className="w-3.5 h-3.5" />}
                          تأكيد يدوي
                        </Button>
                        {canReject && (
                          <Button size="sm" variant="outline" className="gap-1.5"
                            onClick={() => handleReject(r)} disabled={isProcessing}>
                            <XCircle className="w-3.5 h-3.5" />رفض
                          </Button>
                        )}
                      </div>
                    )}

                    {/* Rescan */}
                    {canRescan && (
                      <Button size="sm" variant="secondary" className="w-full gap-1.5"
                        onClick={() => handleRescan(r)} disabled={isProcessing}>
                        <RotateCcw className="w-3.5 h-3.5" />إعادة الفحص
                      </Button>
                    )}

                    {/* Reopen */}
                    {canReopen && (
                      <Button size="sm" variant="secondary"
                        className="w-full gap-1.5 text-violet-600 border-violet-400/30"
                        onClick={() => handleReopen(r)} disabled={isProcessing}>
                        <RefreshCw className="w-3.5 h-3.5" />إعادة فتح الطلب
                      </Button>
                    )}

                    {/* Open case */}
                    {canCase && (
                      <Button size="sm" variant="outline" className="w-full gap-1.5"
                        onClick={() => setCaseTarget(r)} disabled={isProcessing}>
                        <FolderOpen className="w-3.5 h-3.5" />فتح قضية
                      </Button>
                    )}

                    {/* Duplicate manual review */}
                    {isDuplicate && (
                      <Button size="sm" variant="outline"
                        className="w-full gap-1.5 border-purple-400/30 text-purple-600"
                        onClick={() => handleRescan(r)} disabled={isProcessing}>
                        <Search className="w-3.5 h-3.5" />مراجعة يدوية للمكرر
                      </Button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Open-case dialog */}
      <CaseDialog
        open={!!caseTarget}
        onClose={() => setCaseTarget(null)}
        onConfirm={handleOpenCase}
        orderNum={caseTarget?.order_number}
      />
    </AdminLayout>
  );
}
