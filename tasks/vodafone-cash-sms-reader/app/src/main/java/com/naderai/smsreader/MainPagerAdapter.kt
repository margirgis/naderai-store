package com.naderai.smsreader

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private val pages: List<Pair<String, Fragment>> = listOf(
        "الرئيسية" to HomeFragment(),
        "الإشعارات" to NotificationsFragment(),
        "الكل" to OrdersFragment.newInstance(null),
        "قيد المراجعة" to OrdersFragment.newInstance(OrderStatus.PENDING),
        "جاري البحث" to OrdersFragment.newInstance(OrderStatus.SCANNING),
        "تم العثور" to OrdersFragment.newInstance(OrderStatus.FOUND),
        "تم التأكيد" to OrdersFragment.newInstance(OrderStatus.CONFIRMED),
        "غير مطابق" to OrdersFragment.newInstance(OrderStatus.AMOUNT_MISMATCH),
        "لم يوجد" to OrdersFragment.newInstance(OrderStatus.NOT_FOUND),
        "فشل" to OrdersFragment.newInstance(OrderStatus.FAILED),
        "تشخيص" to DiagnosticsFragment(),
        "الإعدادات" to SettingsFragment()
    )

    fun getTitle(position: Int): String = pages[position].first

    override fun getItemCount(): Int = pages.size

    override fun createFragment(position: Int): Fragment = pages[position].second
}
