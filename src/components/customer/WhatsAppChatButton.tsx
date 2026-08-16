import React, { useEffect, useState } from 'react';
import { MessageCircle } from 'lucide-react';
import { useAuth } from '@/contexts/AuthContext';

const SUPPORT_PHONE = '201222692182'; // رقم واتساب الدعم (مصر +20)

export default function WhatsAppChatButton() {
  const { profile } = useAuth();
  const [visible, setVisible] = useState(false);

  // رسالة ترحيبية باسم المستخدم
  const userName = profile?.email ? profile.email.split('@')[0] : '';

  const message = encodeURIComponent(
    `السلام عليكم، ${userName ? `أنا ${userName} ` : ''}من متجر Nader AI. أبغى استفسار عن شحن رصيد المحفظة.`.trim()
  );
  const waHref = `https://wa.me/${SUPPORT_PHONE}?text=${message}`;

  // تظهر الرسالة بسحر بعد 300ms ثم تبقى ظاهرة
  useEffect(() => {
    const t = setTimeout(() => setVisible(true), 300);
    return () => clearTimeout(t);
  }, []);

  return (
    <div
      className="fixed bottom-[calc(4rem+env(safe-area-inset-bottom))] right-4 md:bottom-6 md:right-6 z-50 flex flex-col items-center"
      dir="rtl"
      aria-label="التواصل عبر واتساب"
    >
      {/* Tooltip صغير وشفاف فوق الزر */}
      <div
        className={`
          relative mb-2 max-w-[10rem] bg-background/70 backdrop-blur-sm text-foreground text-[10px] leading-tight
          rounded-full px-3 py-1.5 shadow-sm border border-border/40 text-center
          transition-all duration-300 pointer-events-none
          ${visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-1'}
        `}
      >
        <span className="flex items-center justify-center gap-1">
          <MessageCircle className="w-3 h-3 text-[#25D366] fill-current" />
          كلمنا على واتساب
        </span>
        <div
          className="absolute -bottom-1 left-1/2 -translate-x-1/2 w-2 h-2 bg-background/70 border-r border-b border-border/40 rotate-45"
          aria-hidden="true"
        />
      </div>

      <a
        href={waHref}
        target="_blank"
        rel="noopener noreferrer"
        style={{ backgroundColor: '#25D366' }}
        className="flex items-center justify-center w-12 h-12 rounded-full text-white shadow-lg hover:scale-105 transition-transform"
        aria-label="فتح واتساب"
      >
        <MessageCircle className="w-6 h-6 fill-current" />
      </a>
    </div>
  );
}
