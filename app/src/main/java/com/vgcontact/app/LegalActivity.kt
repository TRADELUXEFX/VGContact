package com.vgcontact.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Shows either the Terms & Conditions or the Privacy Policy. */
class LegalActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TYPE = "legal_type"
        private const val TYPE_TERMS = "terms"
        private const val TYPE_PRIVACY = "privacy"

        fun openTerms(context: Context) = open(context, TYPE_TERMS)
        fun openPrivacy(context: Context) = open(context, TYPE_PRIVACY)

        private fun open(context: Context, type: String) {
            context.startActivity(
                Intent(context, LegalActivity::class.java).putExtra(EXTRA_TYPE, type)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legal)

        val isPrivacy = intent.getStringExtra(EXTRA_TYPE) == TYPE_PRIVACY
        BackHeader.bind(this, if (isPrivacy) "Privacy Policy" else "Terms & Conditions")
        findViewById<TextView>(R.id.legal_body).text =
            if (isPrivacy) LegalContent.PRIVACY else LegalContent.TERMS
    }
}
