import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Loader2, AlertCircle, CheckCircle2, Wrench, Info,
  HardDrive, Zap, Bot, Calendar, AlertTriangle,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { supabase } from '@/db/supabase';
import type { ProviderService } from '@/types/types';
import { ServiceDetailsModal } from '@/components/customer/ServiceDetailsModal';

export default function ServiceCatalogPage() {
  const navigate = useNavigate();
  const [services, setServices] = useState<ProviderService[]>([]);
  const [loading, setLoading] = useState(true);
  const [detailSvc, setDetailSvc] = useState<ProviderService | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    const { data } = await supabase
      .from('provider_services')
      .select('*')
      .eq('store_enabled', true)
      .order('created_at', { ascending: true });
    setServices((data ?? []) as ProviderService[]);
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  return (
    <CustomerLayout>
      <div className="px-4 md:px-6 py-6 max-w-2xl mx-auto space-y-5">
        <div className="space-y-0.5">
          <h1 className="text-xl font-bold text-foreground">الخدمات المتاحة</h1>
          <p className="text-sm text-muted-foreground">خدمات رقمية باشتراكات موثوقة</p>
        </div>

        {loading ? (
          <div className="flex justify-center py-16">
            <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
          </div>
        ) : services.length === 0 ? (
          <div className="text-center py-16 space-y-2">
            <AlertCircle className="w-8 h-8 text-muted-foreground mx-auto" />
            <p className="text-sm text-muted-foreground">لا توجد خدمات متاحة حالياً</p>
          </div>
        ) : (
          <div className="space-y-4">
            {services.map(svc => {
              const isAvailable = svc.status === 'active';
              const unitPrice = svc.customer_price ?? svc.final_credit_price ?? 0;
              return (
                <div key={svc.id} className="bg-card border border-border rounded-2xl overflow-hidden shadow-sm">
                  <div className="h-1 bg-primary" />
                  <div className="p-5 space-y-4">
                    {/* Title row */}
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2 flex-wrap mb-1.5">
                          <Badge className="bg-primary/10 text-primary border-primary/20 text-xs font-medium">
                            اشتراك رسمي
                          </Badge>
                          {isAvailable
                            ? <span className="flex items-center gap-1 text-xs text-green-600 font-medium">
                                <CheckCircle2 className="w-3 h-3" />متاح
                              </span>
                            : <span className="flex items-center gap-1 text-xs text-amber-600 font-medium">
                                <Wrench className="w-3 h-3" />صيانة
                              </span>
                          }
                        </div>
                        <h2 className="text-lg font-bold text-foreground">
                          {svc.display_name_ar ?? 'جيميناي برو 18 شهر'}
                        </h2>
                        <p className="text-sm text-muted-foreground">
                          {svc.display_name_en ?? 'Gemini AI Pro — 18 Months'}
                        </p>
                      </div>
                    </div>

                    {/* Short description */}
                    <p className="text-sm text-foreground leading-relaxed">
                      Gemini AI Pro لمدة 18 شهرًا مع 5 TB تخزين و1000 AI Credits شهريًا في Flow.
                    </p>

                    {/* Feature pills — Latin digits */}
                    <div className="flex flex-wrap gap-2">
                      <span className="flex items-center gap-1.5 text-xs text-muted-foreground bg-muted/50 px-2.5 py-1 rounded-full border border-border">
                        <HardDrive className="w-3 h-3 text-primary shrink-0" />5 TB Storage
                      </span>
                      <span className="flex items-center gap-1.5 text-xs text-muted-foreground bg-muted/50 px-2.5 py-1 rounded-full border border-border">
                        <Zap className="w-3 h-3 text-primary shrink-0" />1000 Credit / Month
                      </span>
                      <span className="flex items-center gap-1.5 text-xs text-muted-foreground bg-muted/50 px-2.5 py-1 rounded-full border border-border">
                        <Calendar className="w-3 h-3 text-primary shrink-0" />18 Months
                      </span>
                      <span className="flex items-center gap-1.5 text-xs text-muted-foreground bg-muted/50 px-2.5 py-1 rounded-full border border-border">
                        <Bot className="w-3 h-3 text-primary shrink-0" />Google Gemini AI
                      </span>
                    </div>

                    {/* No-guarantee notice */}
                    <div className="flex items-start gap-2 p-2.5 rounded-lg bg-amber-50 border border-amber-200">
                      <AlertTriangle className="w-3.5 h-3.5 text-amber-600 shrink-0 mt-0.5" />
                      <p className="text-xs text-amber-700">⚠️ لا يوجد ضمان على الخدمة بعد التفعيل.</p>
                    </div>

                    {/* Price + Actions */}
                    <div className="flex items-center justify-between gap-3 pt-1">
                      <div>
                        <p className="text-2xl font-bold text-primary">
                          {unitPrice.toFixed(1)}
                          <span className="text-sm font-normal text-muted-foreground"> Credit</span>
                        </p>
                        <p className="text-xs text-muted-foreground">لمدة 18 Months</p>
                      </div>
                      <div className="flex flex-col gap-2 shrink-0">
                        <Button
                          variant="outline"
                          size="sm"
                          className="gap-1.5"
                          onClick={() => setDetailSvc(svc)}
                        >
                          <Info className="w-3.5 h-3.5" />
                          تفاصيل الاشتراك
                        </Button>
                        <Button
                          size="sm"
                          className="gap-1.5 font-semibold"
                          disabled={!isAvailable}
                          onClick={() => navigate(`/store/order/${svc.id}`)}
                        >
                          اشتراك الآن
                        </Button>
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {detailSvc && (
        <ServiceDetailsModal
          svc={detailSvc}
          onClose={() => setDetailSvc(null)}
          onSubscribe={() => { setDetailSvc(null); navigate(`/store/order/${detailSvc.id}`); }}
        />
      )}
    </CustomerLayout>
  );
}
