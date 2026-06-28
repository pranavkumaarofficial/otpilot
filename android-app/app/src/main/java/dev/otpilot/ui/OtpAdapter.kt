package dev.otpilot.ui

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.otpilot.R
import dev.otpilot.databinding.ItemOtpCardBinding
import dev.otpilot.model.OtpMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OtpAdapter(
    private val onCopyClick: (String) -> Unit,
    private var otpTtl: Long = 300_000L
) : ListAdapter<OtpMessage, OtpAdapter.ViewHolder>(DIFF) {

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun setTtl(ttl: Long) {
        otpTtl = ttl
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOtpCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemOtpCardBinding) : RecyclerView.ViewHolder(binding.root) {
        private var ttlRunnable: Runnable? = null

        fun bind(otp: OtpMessage) {
            val ctx = binding.root.context

            // Set category background
            val bgRes = when (otp.category) {
                "delivery" -> R.drawable.bg_otp_card_delivery
                "food" -> R.drawable.bg_otp_card_food
                "transport" -> R.drawable.bg_otp_card_transport
                "bank" -> R.drawable.bg_otp_card_bank
                else -> R.drawable.bg_otp_card_default
            }
            binding.cardRoot.setBackgroundResource(bgRes)

            // Tags
            binding.appTag.text = if (otp.app != "unknown") otp.app.uppercase() else ""
            binding.memberTag.text = otp.memberName.uppercase()

            // OTP code
            if (otp.otp != null) {
                binding.otpCode.text = otp.otp
                binding.otpCode.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
                binding.otpCode.textSize = 28f
                binding.otpCode.letterSpacing = 0.1f
            } else {
                binding.otpCode.text = ctx.getString(R.string.no_otps_title)
                binding.otpCode.setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
                binding.otpCode.textSize = 13f
                binding.otpCode.letterSpacing = 0f
            }

            // Body
            binding.msgBody.text = otp.body

            // Footer
            binding.fromText.text = otp.from
            binding.timeText.text = timeFormat.format(Date(otp.timestamp))

            // TTL bar
            ttlRunnable?.let { handler.removeCallbacks(it) }
            updateTtl(otp.timestamp)

            // Click to copy
            binding.root.setOnClickListener {
                otp.otp?.let { code -> onCopyClick(code) }
            }
        }

        private fun updateTtl(timestamp: Long) {
            val elapsed = System.currentTimeMillis() - timestamp
            val remaining = (otpTtl - elapsed).coerceAtLeast(0)
            val progress = ((remaining.toFloat() / otpTtl) * 1000).toInt()
            binding.ttlBar.progress = progress

            if (remaining > 0) {
                ttlRunnable = Runnable { updateTtl(timestamp) }
                handler.postDelayed(ttlRunnable!!, 1000)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<OtpMessage>() {
            override fun areItemsTheSame(old: OtpMessage, new: OtpMessage) = old.id == new.id
            override fun areContentsTheSame(old: OtpMessage, new: OtpMessage) = old == new
        }
    }
}
