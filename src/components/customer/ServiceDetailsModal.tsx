import React from 'react';
import {
  X, Bot, HardDrive, Zap, Briefcase, BookOpen, FileText,
  Image, Globe, Code2, FlaskConical, AlertTriangle, Shield,
  Mail, FileSpreadsheet, Presentation, Video, Search, Mic2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import type { ProviderService } from '@/types/types';

/* ── Feature item ───────────────────────────────────────────────────── */
function FeatureItem({ icon: Icon, name, desc }: { icon: React.ElementType; name: string; desc: string }) {
  return (
    <div className="flex items-start gap-3 p-3 rounded-xl bg-muted/30 border border-border">
      <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
        <Icon className="w-4 h-4 text-primary" />
      </div>
      <div className="min-w-0">
        <p className="text-sm font-semibold text-foreground">{name}</p>
        <p className="text-xs text-muted-foreground leading-relaxed">{desc}</p>
      </div>
    </div>
  );
}

/* ── Section ────────────────────────────────────────────────────────── */
function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-bold text-foreground border-b border-border pb-1">{title}</h3>
      <div className="space-y-2">{children}</div>
    </div>
  );
}

interface Props { svc: ProviderService; onClose: () => void; onSubscribe: () => void }

export function ServiceDetailsModal({ svc, onClose, onSubscribe }: Props) {
  const unitPrice = svc.customer_price ?? svc.final_credit_price ?? 0;
  const isAvailable = svc.status === 'active';

  return (
    <div
      className="fixed inset-0 z-50 flex items-end md:items-center justify-center p-4 bg-black/50 backdrop-blur-sm"
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="bg-card w-full max-w-[calc(100%-2rem)] md:max-w-lg rounded-2xl shadow-2xl border border-border overflow-hidden max-h-[92dvh] flex flex-col">
        {/* Header */}
        <div className="relative bg-primary px-5 pt-5 pb-6 shrink-0">
          <button
            onClick={onClose}
            className="absolute top-4 left-4 w-7 h-7 rounded-full bg-white/20 flex items-center justify-center text-white hover:bg-white/30 transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
          <Badge className="bg-white/20 text-white border-white/30 text-xs mb-2">اشتراك رسمي</Badge>
          <h2 className="text-xl font-bold text-white">
            {svc.display_name_ar ?? 'جيميناي برو 18 شهر'}
          </h2>
          <p className="text-sm text-white/80 mt-0.5">
            {svc.display_name_en ?? 'Gemini AI Pro — 18 Months'}
          </p>
          {/* Price + duration row */}
          <div className="flex items-center gap-4 mt-3">
            <div className="bg-white/15 rounded-lg px-3 py-1.5 text-center">
              <p className="text-xs text-white/70">السعر</p>
              <p className="text-lg font-bold text-white">{unitPrice.toFixed(1)} Credit</p>
            </div>
            <div className="bg-white/15 rounded-lg px-3 py-1.5 text-center">
              <p className="text-xs text-white/70">المدة</p>
              <p className="text-lg font-bold text-white">18 Months</p>
            </div>
            <div className="bg-white/15 rounded-lg px-3 py-1.5 text-center">
              <p className="text-xs text-white/70">الأيام</p>
              <p className="text-lg font-bold text-white">540 Days</p>
            </div>
          </div>
        </div>

        {/* Content */}
        <div className="overflow-y-auto flex-1 px-5 py-4 space-y-5">

          {/* Section 1: Gemini AI */}
          <Section title="🤖 Gemini AI">
            <FeatureItem icon={Bot}       name="Gemini Pro"         desc="وصول كامل لنماذج Gemini Pro الأحدث ومحادثات ذكاء اصطناعي متقدمة" />
            <FeatureItem icon={Image}     name="تحليل الصور"        desc="فهم وتحليل الصور وملفات PDF والمستندات بالذكاء الاصطناعي" />
            <FeatureItem icon={FileText}  name="إنشاء المحتوى"      desc="كتابة المقالات والملخصات والترجمة وإعداد التقارير" />
            <FeatureItem icon={Search}    name="Deep Research"      desc="بحث عميق ومتقدم بمساعدة الذكاء الاصطناعي" />
            <FeatureItem icon={BookOpen}  name="NotebookLM"         desc="تنظيم الملاحظات والمصادر بالذكاء الاصطناعي" />
            <FeatureItem icon={Globe}     name="الترجمة"            desc="ترجمة النصوص والمستندات بدقة عالية" />
          </Section>

          {/* Section 2: Google Workspace Integration */}
          <Section title="💼 Google Workspace Integration">
            <FeatureItem icon={Mail}          name="Gemini في Gmail"       desc="مساعد ذكي لكتابة وتلخيص رسائل البريد الإلكتروني" />
            <FeatureItem icon={FileText}      name="Gemini في Docs"         desc="تحرير ومراجعة المستندات بمساعدة AI" />
            <FeatureItem icon={FileSpreadsheet} name="Gemini في Sheets"    desc="تحليل البيانات وإنشاء الصيغ بمساعدة AI" />
            <FeatureItem icon={Presentation}  name="Gemini في Slides"      desc="إنشاء وتحسين العروض التقديمية" />
            <FeatureItem icon={Video}         name="Gemini في Meet"         desc="ملخصات الاجتماعات وتدوين الملاحظات تلقائياً" />
            <FeatureItem icon={HardDrive}     name="Gemini في Drive"        desc="البحث والتنظيم الذكي للملفات" />
          </Section>

          {/* Section 3: Google One — 5 TB */}
          <Section title="☁️ Google One — 5 TB">
            <FeatureItem icon={HardDrive} name="5 TB تخزين سحابي"   desc="5 تيرابايت موزعة على Drive و Gmail و Photos" />
            <FeatureItem icon={Shield}    name="الأمان المتقدم"      desc="حماية إضافية للحساب وإدارة أجهزة متعددة" />
          </Section>

          {/* Section 4: Google Flow — 1000 Credits */}
          <Section title="⚡ Google Flow — 1000 Credit / Month">
            <FeatureItem icon={Zap}       name="1000 AI Credits شهرياً"   desc="رصيد شهري لاستخدام نماذج AI في Flow" />
            <FeatureItem icon={Video}     name="Text to Video"             desc="إنشاء مقاطع فيديو من النص" />
            <FeatureItem icon={Image}     name="Image to Video"            desc="تحويل الصور إلى مقاطع فيديو" />
            <FeatureItem icon={FlaskConical} name="Veo"                   desc="توليد الفيديو بنماذج Veo ضمن الخطة" />
          </Section>

          {/* Section 5: Developer Tools */}
          <Section title="🛠️ أدوات المطورين">
            <FeatureItem icon={Code2}     name="Code Assist"         desc="مساعد البرمجة الذكي داخل بيئات التطوير" />
            <FeatureItem icon={FlaskConical} name="Google AI Studio" desc="منصة تجريب النماذج وبناء التطبيقات" />
            <FeatureItem icon={Mic2}      name="Jules"               desc="وكيل AI مستقل لمهام البرمجة" />
          </Section>

          {/* Important notice */}
          <div className="p-4 rounded-xl bg-amber-50 border border-amber-200 space-y-2">
            <p className="text-xs font-bold text-amber-800 flex items-center gap-1.5">
              <AlertTriangle className="w-3.5 h-3.5" />
              ملاحظة مهمة
            </p>
            <p className="text-xs text-amber-700 leading-relaxed">
              ⚠️ لا يوجد ضمان على الخدمة بعد التفعيل. تأكد من قراءة تفاصيل العرض والتأكد من حساب Google قبل إتمام العملية.
            </p>
            <p className="text-xs text-amber-700">
              المزايا المعروضة مستندة إلى تفاصيل العرض من المزود. بعض الميزات قد تتطلب إعداداً إضافياً من Google.
            </p>
          </div>
        </div>

        {/* Actions */}
        <div className="px-5 pb-5 pt-3 flex flex-col gap-2 shrink-0 border-t border-border bg-card">
          <Button
            className="w-full h-11 text-base font-semibold gap-2"
            disabled={!isAvailable}
            onClick={onSubscribe}
          >
            اشتراك الآن — {unitPrice.toFixed(1)} Credit
          </Button>
          <Button variant="outline" className="w-full" onClick={onClose}>إغلاق</Button>
        </div>
      </div>
    </div>
  );
}
