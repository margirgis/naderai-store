import React, { useEffect, useState } from 'react';
import { MessageCircle, X } from 'lucide-react';
import { useAuth } from '@/contexts/AuthContext';

const SUPPORT_PHONE = '201222692182'; // رقم واتساب الدعم (مصر +20)

export default function WhatsAppChatButton() {
  const { profile } = useAuth();
  const [visible, setVisible] = useState(false);
  const [dismissed, setDismissed] = useState(false);

  // رسالة ترحيبية باسم المستخدم
  const userName = profile?.email ? profile.email.split('@')[0] : '';

  const message = encodeURIComponent(
    `السلام عليكم، ${userName ? `أنا ${userName} ` : ''}من متجر Nader AI. أبغى استفسار عن شحن رصيد المحفظة.`.trim()
  );
  const waHref = `https://wa.me/${SUPPORT_PHONE}?text=${message}`;

  // إظهار التولبية بالوسامة: 5 ثانية أولاً، ثم هيكبا وتتكرر 15 ثانية
  useEffect(() => {
    const showTimer = setTimeout(() => setVisible(true), 500);
    const hideTimer = setTimeout(() => setVisible(false), 5_500);
    const interval = setInterval(() => {
      setVisible(true);
      setTimeout(() => setVisible(false), 5_000);
    }, 15_000);
    return () => {
      clearTimeout(showTimer);
      clearTimeout(hideTimer);
      clearInterval(interval);
    };
  }, []);

  if (dismissed) return null;

  return (
    <div className="fixed bottom-4 right-4 z-40 flex flex-col items-end gap-2">
      {/* Tooltip */}
      <div
        className={`
          max-w-[12rem] bg-card text-foreground text-xs rounded-lg px-3 py-2 shadow border border-border
          transition-all duration-300 pointer-events-none
          ${visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-2'}
        `}
      >
        يمكنك التواصل معنا عبر الواتساب
        <div className="absolute bottom-[-5px] right-5 w-2 h-2 bg-card border-b border-r border-border rotate-45" />
      </div>

      <div className="flex items-center gap-2">
        <a
          href={waHref}
          target="_blank"
          rel="noopener noreferrer"
          style={{ backgroundColor: '#25D366' }}
          className="flex items-center justify-center w-12 h-12 rounded-full text-white shadow-lg hover:scale-105 transition-transform"
          aria-label="التواصل عبر واتساب"
        >
          <MessageCircle className="w-6 h-6 fill-current" />
        </a>
        <button
          onClick={() => setDismissed(true)}
          className="w-6 h-6 rounded-full bg-muted/80 text-muted-foreground hover:text-foreground flex items-center justify-center shadow"
          aria-label="إغلاق"
        >
          <X className="w-3 h-3" />
        </button>
      </div>
    </div>
  );
}
