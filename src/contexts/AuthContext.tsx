import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { Session, User } from '@supabase/supabase-js';
import { supabase } from '@/db/supabase';
import type { Profile } from '@/types/types';

interface AuthContextType {
  session: Session | null;
  user: User | null;
  profile: Profile | null;
  loading: boolean;
  isAdmin: boolean;
  signOut: () => Promise<void>;
  refreshProfile: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType>({
  session: null,
  user: null,
  profile: null,
  loading: true,
  isAdmin: false,
  signOut: async () => {},
  refreshProfile: async () => {},
});

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [session, setSession] = useState<Session | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [loading, setLoading] = useState(true);
  const fetchSeqRef = useRef(0);
  const loadingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // ضمان setLoading(false) بعد 5 ثواني على أقصى تقدير — حماية من الانتظار اللانهائي
  const ensureLoadingReleased = () => {
    if (loadingTimerRef.current) clearTimeout(loadingTimerRef.current);
    loadingTimerRef.current = setTimeout(() => {
      setLoading((prev) => {
        if (prev) console.warn('[AuthContext] loading timeout — force releasing');
        return false;
      });
    }, 5000);
  };

  const fetchProfile = async (userId: string): Promise<Profile | null> => {
    const seq = ++fetchSeqRef.current;
    try {
      const { data, error } = await Promise.race([
        supabase
          .from('profiles')
          .select('id, email, phone, full_name, role, wallet_balance, status, created_at, updated_at')
          .eq('id', userId)
          .maybeSingle(),
        new Promise<{ data: null; error: { message: string } }>((resolve) =>
          setTimeout(() => resolve({ data: null, error: { message: 'timeout' } }), 6000)
        ),
      ]);
      if (error) console.error('[AuthContext] fetchProfile error:', error.message);
      if (seq === fetchSeqRef.current) {
        setProfile((data as Profile | null) ?? null);
        return (data as Profile | null) ?? null;
      }
    } catch (e) {
      console.error('[AuthContext] fetchProfile exception:', e);
    }
    return null;
  };

  const refreshProfile = async () => {
    if (user?.id) await fetchProfile(user.id);
  };

  useEffect(() => {
    let mounted = true;
    ensureLoadingReleased();

    // فحص الجلسة الأولية مع timeout 8 ثواني — يمنع الانتظار اللانهائي
    Promise.race([
      supabase.auth.getSession(),
      new Promise<{ data: { session: null }; error: null }>((resolve) =>
        setTimeout(() => resolve({ data: { session: null }, error: null }), 8000)
      ),
    ])
      .then(async ({ data: { session: s } }) => {
        if (!mounted) return;
        setSession(s);
        setUser(s?.user ?? null);
        if (s?.user) {
          await fetchProfile(s.user.id).catch((e: unknown) =>
            console.error('[AuthContext] fetchProfile failed:', e)
          );
        }
      })
      .catch((e: unknown) => {
        console.error('[AuthContext] getSession failed:', e);
      })
      .finally(() => {
        if (mounted) {
          setLoading(false);
          if (loadingTimerRef.current) clearTimeout(loadingTimerRef.current);
        }
      });

    const { data: { subscription } } = supabase.auth.onAuthStateChange(async (_event, s) => {
      if (!mounted) return;
      setSession(s);
      setUser(s?.user ?? null);
      if (s?.user) {
        // انتظر fetchProfile قبل إيقاف loading — يمنع عرض واجهة المستخدم قبل معرفة دور الحساب
        await fetchProfile(s.user.id).catch((e: unknown) =>
          console.error('[AuthContext] fetchProfile on auth change failed:', e)
        );
      } else {
        setProfile(null);
      }
      // أوقف loading فقط بعد اكتمال fetchProfile
      if (mounted) {
        setLoading(false);
        if (loadingTimerRef.current) clearTimeout(loadingTimerRef.current);
      }
    });

    return () => {
      mounted = false;
      subscription.unsubscribe();
      if (loadingTimerRef.current) clearTimeout(loadingTimerRef.current);
    };
  }, []);

  const signOut = async () => {
    await supabase.auth.signOut();
    setProfile(null);
  };

  return (
    <AuthContext.Provider value={{
      session,
      user,
      profile,
      loading,
      isAdmin: profile?.role === 'admin',
      signOut,
      refreshProfile,
    }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
