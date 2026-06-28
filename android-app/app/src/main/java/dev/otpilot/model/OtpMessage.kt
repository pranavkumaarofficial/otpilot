package dev.otpilot.model

data class OtpMessage(
    val id: String,
    val memberId: String,
    val memberName: String,
    val from: String,
    val body: String,
    val otp: String?,
    val app: String,
    val category: String,
    val timestamp: Long
) {
    companion object {
        private val CAT_MAP = mapOf(
            "amazon" to "delivery", "flipkart" to "delivery", "myntra" to "delivery",
            "meesho" to "delivery", "ajio" to "delivery",
            "swiggy" to "food", "zomato" to "food", "blinkit" to "food",
            "zepto" to "food", "bigbasket" to "food", "dunzo" to "food",
            "uber" to "transport", "ola" to "transport", "rapido" to "transport",
            "phonepe" to "bank", "paytm" to "bank", "gpay" to "bank",
            "hdfc" to "bank", "icici" to "bank", "sbi" to "bank",
            "axis" to "bank", "kotak" to "bank"
        )

        fun categoryFor(app: String): String = CAT_MAP[app.lowercase()] ?: "unknown"
    }
}
