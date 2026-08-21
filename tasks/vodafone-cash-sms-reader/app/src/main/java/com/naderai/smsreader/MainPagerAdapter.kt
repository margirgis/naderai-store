package com.naderai.smsreader

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private val pages: List<Pair<String, Fragment>> = listOf(
        "الرئيسية" to HomeFragment(),
        "الإشعارات" to NotificationsFragment(),
        "الكل" to OrdersFragment.newInstance(emptyList()),
        // "قيد المراجعة" يجمع الطلبات الجديدة (pending) وتلك التي بدأ فحصها (scanning)
        // لأن المستخدم يعتبرها كلها تحت المراجعة حتى تكتمل أو تفشل.
        "قيد المراجعة" to OrdersFragment.newInstance(listOf(OrderStatus.PENDING, OrderStatus.SCANNING, OrderStatus.MATCHING, OrderStatus.MANUAL_REVIEW)),
        "جاري البحث" to OrdersFragment.newInstance(listOf(OrderStatus.SCANNING, OrderStatus.MATCHING)),
        "تم العثور" to OrdersFragment.newInstance(listOf(OrderStatus.MATCHED, OrderStatus.WAITING_CONFIRMATION)),
        "تم التأكيد" to OrdersFragment.newInstance(listOf(OrderStatus.CONFIRMED, OrderStatus.COMPLETED)),
        "غير مطابق" to OrdersFragment.newInstance(listOf(OrderStatus.AMOUNT_MISMATCH, OrderStatus.MANUAL_REVIEW)),
        "لم يوجد" to OrdersFragment.newInstance(OrderStatus.NOT_FOUND),
        "منتهي" to OrdersFragment.newInstance(listOf(OrderStatus.EXPIRED, OrderStatus.DUPLICATE)),
        "فشل" to OrdersFragment.newInstance(OrderStatus.FAILED),
        "تشخيص" to DiagnosticsFragment(),
        "مراقبة 🔬" to OrderMonitorFragment(),
        "الإعدادات" to SettingsFragment()
    )

    fun getTitle(position: Int): String = pages[position].first

    override fun getItemCount(): Int = pages.size

    override fun createFragment(position: Int): Fragment = pages[position].second
}
