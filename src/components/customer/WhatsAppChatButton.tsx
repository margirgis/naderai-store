import React from 'react';
import { MessageCircle } from 'lucide-react';
import { useAuth } from '@/contexts/AuthContext';

const SUPPORT_PHONE = '201222692182'; // رقم واتساب الدعم (مصر +20)

export default function WhatsAppChatButton() {
  const { profile } = useAuth();

  // رسالة ترحيبية باسم المستخدم
  const userName = profile?.email ? profile.email.split('@')[0] : '';

  const message = encodeURIComponent(
    `السلام عليكم، ${userName ? `أنا ${userName} ` : ''}من متجر Nader AI. أبغى استفسار عن شحن رصيد المحفظة.`.trim()
  );
  const waHref = `https://wa.me/${SUPPORT_PHONE}?text=${message}`;

  return (
    <div
      className="fixed top-1/2 right-0 -translate-y-1/2 z-50 flex items-center gap-2 pe-2"
      dir="rtl"
      aria-label="التواصل عبر واتساب"
    >
      {/* Tooltip صغير على يسار الزر */}
      <div className="relative bg-card text-foreground text-[10px] leading-tight rounded-md px-2.5 py-1.5 shadow border border-border whitespace-nowrap text-right">
        يمكنك التواصل معنا عبر الواتساب
        <div
          className="absolute top-1/2 -right-[5px] -translate-y-1/2 w-2 h-2 bg-card border-r border-t border-border rotate-45"
          aria-hidden="true"
        />
      </div>

      <a
        href={waHref}
        target="_blank"
        rel="noopener noreferrer"
        style={{ backgroundColor: '#25D366' }}
        className="flex items-center justify-center w-12 h-12 rounded-full text-white shadow-lg hover:scale-105 transition-transform shrink-0"
        aria-label="فتح واتساب"
      >
        <MessageCircle className="w-6 h-6 fill-current" />
      </a>
    </div>
  );
}
