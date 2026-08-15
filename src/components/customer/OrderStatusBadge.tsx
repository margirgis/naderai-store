import { cn } from '@/lib/utils';
import type { OrderStatus } from '@/types/types';

const STATUS_MAP: Record<OrderStatus, { label: string; className: string }> = {
  creating:   { label: 'جارٍ الإنشاء',      className: 'text-muted-foreground bg-muted/50 border-border' },
  queued:     { label: 'في الانتظار',        className: 'text-yellow-400 bg-yellow-400/10 border-yellow-400/20' },
  processing: { label: 'قيد التنفيذ',        className: 'text-blue-400 bg-blue-400/10 border-blue-400/20' },
  success:    { label: 'مكتمل',              className: 'text-green-400 bg-green-400/10 border-green-400/20' },
  partial:    { label: 'مكتمل جزئياً',       className: 'text-cyan-400 bg-cyan-400/10 border-cyan-400/20' },
  failed:     { label: 'فشل',               className: 'text-destructive bg-destructive/10 border-destructive/20' },
  cancelled:  { label: 'ملغي',              className: 'text-muted-foreground bg-muted/50 border-border' },
  rejected:   { label: 'مرفوض',             className: 'text-orange-400 bg-orange-400/10 border-orange-400/20' },
};

export function OrderStatusBadge({ status }: { status: OrderStatus | string }) {
  const config = STATUS_MAP[status as OrderStatus] ?? { label: status, className: 'text-muted-foreground bg-muted/50 border-border' };
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border', config.className)}>
      {config.label}
    </span>
  );
}
