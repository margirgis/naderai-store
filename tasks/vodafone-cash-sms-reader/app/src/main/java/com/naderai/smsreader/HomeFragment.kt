package com.naderai.smsreader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.naderai.smsreader.databinding.FragmentHomeBinding
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeState()
        showDeviceInfo()
    }

    private fun showDeviceInfo() {
        val ctx = requireContext()
        binding.deviceIdValue.text = HeartbeatManager.getDeviceId(ctx).take(16) + "…"
        binding.deviceModelValue.text = android.os.Build.MODEL ?: "—"
        binding.androidVersionValue.text = android.os.Build.VERSION.RELEASE ?: "—"
        binding.appVersionValue.text = "1.0.4"
    }

    private fun observeState() {
        AppState.isConnected.observe(viewLifecycleOwner) { connected ->
            binding.statusDot.setBackgroundResource(
                if (connected) R.drawable.status_online else R.drawable.status_offline
            )
            binding.statusLabel.text = if (connected) "متصل بالسيرفر" else "غير متصل"
            binding.statusLabel.setTextColor(
                resources.getColor(if (connected) android.R.color.holo_green_dark else android.R.color.holo_red_dark, null)
            )
        }

        AppState.connectionMessage.observe(viewLifecycleOwner) { msg ->
            binding.connectionMessageText.text = msg
        }

        AppState.lastSyncTime.observe(viewLifecycleOwner) { ts ->
            binding.lastSyncValue.text = if (ts != null) formatTime(ts) else "—"
        }

        AppState.lastSmsScannedAt.observe(viewLifecycleOwner) { ts ->
            binding.lastSmsValue.text = if (ts != null) formatTime(ts) else "—"
        }

        AppState.lastFoundTransaction.observe(viewLifecycleOwner) { txId ->
            binding.lastTransactionValue.text = txId?.take(16)?.plus("…") ?: "—"
        }

        AppState.pendingCount.observe(viewLifecycleOwner) { binding.pendingCountText.text = it.toString() }
        AppState.confirmedCount.observe(viewLifecycleOwner) { binding.confirmedCountText.text = it.toString() }
        AppState.failedCount.observe(viewLifecycleOwner) { binding.failedCountText.text = it.toString() }
        AppState.notFoundCount.observe(viewLifecycleOwner) { binding.notFoundCountText.text = it.toString() }

        AppState.pendingTasks.observe(viewLifecycleOwner) { tasks ->
            binding.activeScanBadge.visibility = if (tasks.isNotEmpty()) View.VISIBLE else View.GONE
            binding.activeScanBadge.text = "يفحص ${tasks.size} طلب…"
        }
    }

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
