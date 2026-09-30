package com.vgcontact.app

/**
 * Text shown on the Terms & Conditions and Privacy Policy screens.
 *
 * !! PLACEHOLDER COPY !! This is a generic starting draft so the screens
 * work end to end. It is NOT legal advice and must be reviewed and
 * replaced by your own lawyer-approved text before release. Edit only
 * this file; nothing else needs to change.
 */
object LegalContent {

    const val LAST_UPDATED = "Last updated: 29 September 2026"
    const val OPERATOR = "VGCONTACT"
    const val SUPPORT_CONTACT = "WhatsApp +234 911 032 1143"

    val TERMS = """
$LAST_UPDATED

1. About these terms
By creating an account or using VGContact you agree to these Terms & Conditions. If you do not agree, please do not use the app. VGContact is operated by $OPERATOR.

2. What the app does
VGContact helps you grow your WhatsApp status viewers. You are placed in a group of users, and the app saves the other verified members of your groups to your phone as contacts so that you and they can see each other's statuses. You can get more viewers by reposting and having the repost verified, and by referring other people.

3. Accounts
You must give accurate information when you register and keep your login details safe. You are responsible for activity on your account.

4. Extra viewers and payments
Free viewers come with your group. Extra viewers are added when a repost is verified (up to the limit shown in the app). You can also ask to buy more status viewers by contacting support on WhatsApp; prices and payment terms are agreed there, and unless the law requires otherwise, payments are non-refundable once viewers have been added.

5. Acceptable use
You agree not to misuse the app, including by spamming or harassing the people whose numbers you save, using saved contacts unlawfully, submitting false reposts, attempting to bypass group or repost limits, or interfering with the service. We may suspend or ban accounts that break these rules.

6. Contacts and third parties
Saved contacts may contain phone numbers that belong to other people. You agree to use them lawfully and respectfully and to follow the rules of any messaging platform you use them on.

7. Availability
We work to keep the app running but do not promise it will always be available or error free. We may change or stop features at any time.

8. Liability
To the extent the law allows, $OPERATOR is not liable for indirect or consequential losses arising from your use of the app.

9. Changes
We may update these terms. Continued use after an update means you accept the new terms.

10. Contact
Questions? Reach us on $SUPPORT_CONTACT.
""".trimIndent()

    val PRIVACY = """
$LAST_UPDATED

1. Overview
This policy explains what information VGContact collects, why, and the choices you have. VGContact is operated by $OPERATOR.

2. Information we collect
- Account details: username and phone number you provide at registration.
- Usage data: your group memberships, reposts and their verification status, and referrals.
- Device data: app version and a push notification token so we can send you notifications.
- Contacts: the app saves the contacts of verified group members to your phone. We do not read your existing phone contacts unless a feature clearly says so and you grant permission.

3. How we use it
To run your account, manage your groups, verify reposts, count referrals, sync contacts, prevent abuse and fraud, send service and push notifications, provide support, and improve the app.

4. Sharing
We do not sell your personal information. We use service providers to run the app (for example hosting, database and push notification services) and may disclose information if the law requires it.

5. Storage and security
Your data is stored with our backend providers and protected with reasonable safeguards. No system is perfectly secure.

6. Retention and deletion
We keep your information while your account is active. To ask for your data to be deleted, contact us using the details below.

7. Your choices
You can turn off notifications in your phone settings, and revoke permissions at any time.

8. Children
VGContact is not intended for children under 18.

9. Changes
We may update this policy. Continued use after an update means you accept it.

10. Contact
Questions or requests? Reach us on $SUPPORT_CONTACT.
""".trimIndent()
}
