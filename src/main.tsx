import * as Sentry from "@sentry/react";
import { createRoot } from "react-dom/client";
import App from "./App.tsx";
import { AppWrapper } from "./components/common/PageMeta.tsx";
import "./index.css";

Sentry.init({
  dsn: import.meta.env['VITE_SENTRY_DSN'] as string | undefined,
  environment: import.meta.env.MODE,
});

const ErrorFallback = () => (
  <div style={{ display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', minHeight:'100vh', background:'#0f172a', color:'#f8fafc', fontFamily:'sans-serif', gap:'16px' }}>
    <h2 style={{ fontSize:'1.5rem' }}>حدث خطأ غير متوقع</h2>
    <p style={{ color:'#94a3b8' }}>يرجى تحديث الصفحة</p>
    <button onClick={() => window.location.reload()} style={{ padding:'10px 24px', background:'#7c3aed', color:'#fff', border:'none', borderRadius:'8px', cursor:'pointer', fontSize:'1rem' }}>
      تحديث الصفحة
    </button>
  </div>
);

createRoot(document.getElementById("root")!).render(
  <Sentry.ErrorBoundary fallback={<ErrorFallback />}>
    <AppWrapper>
      <App />
    </AppWrapper>
  </Sentry.ErrorBoundary>
);
