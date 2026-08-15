package com.naderai.smsreader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.naderai.smsreader.databinding.FragmentOrdersBinding

class OrdersFragment : Fragment() {

    private var _binding: FragmentOrdersBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: OrderAdapter
    private var filterStatus: OrderStatus? = null

    companion object {
        private const val ARG_STATUS = "status"
        fun newInstance(status: OrderStatus? = null): OrdersFragment {
            val f = OrdersFragment()
            if (status != null) {
                f.arguments = Bundle().apply { putString(ARG_STATUS, status.name) }
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val statusName = arguments?.getString(ARG_STATUS)
        filterStatus = if (statusName != null) OrderStatus.valueOf(statusName) else null

        adapter = OrderAdapter()
        binding.ordersRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.ordersRecycler.adapter = adapter

        AppState.orders.observe(viewLifecycleOwner) { orders ->
            val filtered = if (filterStatus != null) orders.filter { it.status == filterStatus } else orders
            adapter.submitList(filtered)
            binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            binding.emptyText.text = if (filterStatus != null)
                "لا توجد طلبات في حالة ${filterStatus!!.label}"
            else "لا توجد طلبات بعد"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
