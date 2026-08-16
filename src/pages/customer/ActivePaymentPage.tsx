/**
 * ActivePaymentPage — صفحة "إكمال الدفع" في الشريط الجانبي
 * تجلب الطلب المفتوح الحالي للمستخدم أو تعرض حالة فارغة.
 */
import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  CreditCard, Loader2, Clock, CheckCircle2, AlertCircle,
  RefreshCcw, ArrowLeft,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { supabase } from '@/db/supabase';
import { useAuth } from '@/contexts/AuthContext';

interface ActiveOrder {
  order_id: string;
  order_number: number;
  status: string;
  credits_qty: number;
  expected_amount: number;
  expires_at: string;
  has_active: boolean;
}

function SecondsLeft({ expiresAt }: { expiresAt: string }) {
  const calc = () => Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));
  const [s, setS] = React.useState(calc);
  useEffect(() => {
    const id = setInterval(() => setS(calc), 1000);
    return () => clearInterval(id);
  });
  const m = Math.floor(s / 60);
  const sec = s % 60;
  const crit = s < 120;
  if (s === 0) return <span className="text-destructive font-bold text-sm">انتهت الصلاحية</span>;
  return (
    <span className={`font-mono font-bold text-sm tabular-nums ${crit ? 'text-destructive' : 'text-amber-600'}`}>
      {String(m).padStart(2, '0')}:{String(sec).padStart(2, '0')}
    </span>
  );
}

export default function ActivePaymentPage() {
  const navigate    = useNavigate();
  const { profile } = useAuth();
  const [data,    setData]    = useState<ActiveOrder | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!profile?.id) return;
    setLoading(true);
    const { data: result } = await supabase.rpc('get_active_payment_order', {
      p_user_id: profile.id,
    });
    setData((result as ActiveOrder) ?? null);
    setLoading(false);
  }, [profile?.id]);

  useEffect(() => { load(); }, [load]);

  return (
    <CustomerLayout>
      <div className="px-4 md:px-6 py-6 max-w-lg mx-auto space-y-5">
        <div className="space-y-1">
          <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
            <CreditCard className="w-5 h-5 text-primary" />
            إكمال الدفع
          </h1>
          <p className="text-sm text-muted-foreground">طلب الشحن المفتوح الحالي</p>
        </div>

        {loading ? (
          <div className="flex justify-center py-16">
            <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
          </div>
        ) : data?.has_active ? (
          <Card className="border-border shadow-sm overflow-hidden">
            <div className="h-1.5 bg-primary" />
            <CardContent className="pt-5 space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-xs text-muted-foreground">رقم الطلب</p>
                  <p className="text-lg font-bold font-mono text-foreground">#{data.order_number}</p>
                </div>
                <div className="text-left">
                  <p className="text-xs text-muted-foreground mb-0.5">الوقت المتبقي</p>
                  <SecondsLeft expiresAt={data.expires_at} />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="rounded-lg bg-muted/30 p-3">
                  <p className="text-xs text-muted-foreground">الكريدات</p>
                  <p className="text-base font-bold text-primary">{data.credits_qty} Credit</p>
                </div>
                <div className="rounded-lg bg-muted/30 p-3">
                  <p className="text-xs text-muted-foreground">المبلغ</p>
                  <p className="text-base font-bold text-primary" dir="ltr">{data.expected_amount.toFixed(2)} جنيه</p>
                </div>
              </div>

              <div className="flex items-center gap-2 p-3 rounded-xl bg-amber-50 border border-amber-200">
                <Clock className="w-4 h-4 text-amber-600 shrink-0" />
                <p className="text-xs text-amber-700 leading-relaxed">
                  يجب تحويل المبلغ بالقروش بالضبط <strong dir="ltr">{data.expected_amount.toFixed(2)} جنيه</strong> قبل انتهاء الوقت.
                </p>
              </div>

              <Button
                className="w-full h-11 font-semibold gap-2"
                onClick={() => navigate(`/store/wallet/payment/${data.order_id}`)}
              >
                <CheckCircle2 className="w-4 h-4" />
                متابعة إكمال الدفع
              </Button>
            </CardContent>
          </Card>
        ) : (
          <Card className="border-border shadow-sm">
            <CardContent className="pt-10 pb-10">
              <div className="flex flex-col items-center gap-4 text-center">
                <div className="w-14 h-14 rounded-full bg-muted/40 flex items-center justify-center">
                  <AlertCircle className="w-7 h-7 text-muted-foreground" />
                </div>
                <div>
                  <p className="text-sm font-semibold text-foreground">لا يوجد طلب دفع مفتوح</p>
                  <p className="text-xs text-muted-foreground mt-1">
                    لا يوجد طلب شحن نشط حالياً. أنشئ طلباً جديداً من صفحة الشحن.
                  </p>
                </div>
                <div className="flex flex-col gap-2 w-full max-w-xs">
                  <Button className="gap-2" onClick={() => navigate('/store/wallet/topup')}>
                    <CreditCard className="w-4 h-4" />
                    إنشاء طلب شحن جديد
                  </Button>
                  <Button variant="outline" className="gap-2" onClick={load}>
                    <RefreshCcw className="w-4 h-4" />
                    تحديث
                  </Button>
                </div>
              </div>
            </CardContent>
          </Card>
        )}

        <button
          onClick={() => navigate('/store/wallet')}
          className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          العودة للمحفظة
        </button>
      </div>
    </CustomerLayout>
  );
}
