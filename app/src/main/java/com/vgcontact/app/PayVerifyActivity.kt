package com.vgcontact.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Pay to verify. Opened from the pending sheet's Pay button.
 *
 * Shows the amount and the bank details (from app_settings, via [PendingConfig]). After paying,
 * the user taps "Send proof": that opens a WhatsApp chat with the admin with a ready message
 * (the same way the pending sheet's "Send proof" does), and the user attaches the receipt.
 * There is no payment API and nothing is stored on the phone: the owner verifies by hand.
 */
class PayVerifyActivity : BaseActivity() {

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, PayVerifyActivity::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pay_verify)
        window.statusBarColor = ContextCompat.getColor(this, R.color.vg_green)

        findViewById<View>(R.id.payBack).setOnClickListener { finish() }
        findViewById<View>(R.id.payCopyAccount).setOnClickListener {
            copy("Account number", PendingConfig.payAccountNumber(this), R.id.payCopyAccountText)
        }
        findViewById<View>(R.id.payProofBtn).setOnClickListener { sendProof() }
        pressEffect(findViewById(R.id.payProofBtn))

        // Bank details are cached by PendingConfig (saved from Home's single server call).
        handler.postDelayed({ if (!isFinishing) render() }, 1500)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun render() {
        val accountNumber = PendingConfig.payAccountNumber(this)
        findViewById<TextView>(R.id.payAmount).text = PendingConfig.payAmount(this)
        findViewById<TextView>(R.id.payBankName).text = PendingConfig.payBank(this)
        findViewById<TextView>(R.id.payAccountName).text = PendingConfig.payAccountName(this)
        findViewById<TextView>(R.id.payAccountNumber).text = accountNumber
        findViewById<TextView>(R.id.payNote).text =
            getString(R.string.pay_note, PendingConfig.verifyHours(this))

        // Never let someone pay an empty account: with no details, hide Copy and Send proof
        // and say so (they can still reach support from the pending sheet).
        val hasDetails = accountNumber.isNotBlank()
        findViewById<View>(R.id.payDetailsMissing).visibility = if (hasDetails) View.GONE else View.VISIBLE
        findViewById<View>(R.id.payCopyAccount).visibility = if (hasDetails) View.VISIBLE else View.GONE
        findViewById<View>(R.id.payProofWrap).visibility = if (hasDetails) View.VISIBLE else View.GONE
    }

    /** Opens WhatsApp to the admin with the proof message (user attaches the screenshot there). */
    private fun sendProof() {
        val username = SessionManager(this).getUsername()
        SupportContact.openSupport(
            this,
            getString(R.string.pay_msg_proof, PendingConfig.payAmount(this)) +
                if (username.isNullOrBlank()) "" else getString(R.string.pay_msg_username, username)
        )
    }

    private fun copy(label: String, value: String, textViewId: Int) {
        if (value.isBlank()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        val tv = findViewById<TextView>(textViewId)
        tv.setText(R.string.pay_copied)
        handler.postDelayed({ tv.setText(R.string.pay_copy) }, 1500)
    }

    /** Small "press in" feedback, same as the pending sheet. Returns false so the click still fires. */
    private fun pressEffect(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }
}
