package com.naderai.smsreader

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Fix perf: كل Fragment تُنشأ عند الطلب (lazy factory) لا في constructor.
 * كان النمط القديم يُنشئ 14 Fragment دفعةً واحدة عند بناء الـ Adapter
 * قبل أن يستدعي FragmentStateAdapter.createFragment() أياً منها.
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    // عناوين التبويبات فقط — بدون Fragment instances
    private val titles = listOf(
        "الرئيسية",
        "الإشعارات",
        "الكل",
        "قيد المراجعة",
        "جاري البحث",
        "تم العثور",
        "تم التأكيد",
        "غير مطابق",
        "لم يوجد",
        "منتهي",
        "فشل",
        "تشخيص",
        "مراقبة 🔬",
        "الإعدادات"
    )

    fun getTitle(position: Int): String = titles[position]

    override fun getItemCount(): Int = titles.size

    // يُستدعى بواسطة ViewPager2 فقط عند الحاجة الفعلية للـ Fragment (lazy)
    override fun createFragment(position: Int): Fragment = when (position) {
        0  -> HomeFragment()
        1  -> NotificationsFragment()
        2  -> OrdersFragment.newInstance(emptyList())
        3  -> OrdersFragment.newInstance(listOf(OrderStatus.PENDING, OrderStatus.SCANNING, OrderStatus.MATCHING, OrderStatus.MANUAL_REVIEW))
        4  -> OrdersFragment.newInstance(listOf(OrderStatus.SCANNING, OrderStatus.MATCHING))
        5  -> OrdersFragment.newInstance(listOf(OrderStatus.MATCHED, OrderStatus.WAITING_CONFIRMATION))
        6  -> OrdersFragment.newInstance(listOf(OrderStatus.CONFIRMED, OrderStatus.COMPLETED))
        7  -> OrdersFragment.newInstance(listOf(OrderStatus.AMOUNT_MISMATCH, OrderStatus.MANUAL_REVIEW))
        8  -> OrdersFragment.newInstance(OrderStatus.NOT_FOUND)
        9  -> OrdersFragment.newInstance(listOf(OrderStatus.EXPIRED, OrderStatus.DUPLICATE))
        10 -> OrdersFragment.newInstance(OrderStatus.FAILED)
        11 -> DiagnosticsFragment()
        12 -> OrderMonitorFragment()
        13 -> SettingsFragment()
        else -> throw IllegalArgumentException("تبويب غير معروف: $position")
    }
}
