package nl.icthorse.randomringtone.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

data class ContactInfo(
    val uri: String,
    val name: String,
    val photoUri: String? = null
)

/**
 * Leest contacten en stelt per-contact ringtone in via ContactsContract.
 */
class ContactsRepository(private val context: Context) {

    /**
     * Haal alle contacten op met een telefoonnummer.
     */
    fun getContacts(): List<ContactInfo> {
        val contacts = mutableListOf<ContactInfo>()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_URI,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )

        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val photoCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val photo = cursor.getString(photoCol)
                val uri = ContactsContract.Contacts.getLookupUri(
                    id,
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY))
                ).toString()

                contacts.add(ContactInfo(uri = uri, name = name, photoUri = photo))
            }
        }
        return contacts
    }

    /**
     * Zoek contacten op naam.
     */
    fun searchContacts(query: String): List<ContactInfo> {
        if (query.isBlank()) return getContacts()
        return getContacts().filter { it.name.contains(query, ignoreCase = true) }
    }

    /**
     * Stel een custom ringtone in voor een specifiek contact (telefoonoproep).
     * Gebruikt ContactsContract.Contacts.CUSTOM_RINGTONE.
     */
    fun setContactRingtone(contactUri: String, ringtoneUri: Uri?): Boolean {
        return try {
            val values = ContentValues().apply {
                put(
                    ContactsContract.Contacts.CUSTOM_RINGTONE,
                    ringtoneUri?.toString()
                )
            }
            val uri = Uri.parse(contactUri)
            val contactId = getContactIdFromUri(uri) ?: return false
            val updateUri = ContactsContract.Contacts.CONTENT_URI.buildUpon()
                .appendPath(contactId.toString())
                .build()
            context.contentResolver.update(updateUri, values, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verwijder custom ringtone voor een contact (terug naar standaard).
     */
    fun clearContactRingtone(contactUri: String): Boolean {
        return setContactRingtone(contactUri, null)
    }

    private fun getContactIdFromUri(uri: Uri): Long? {
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts._ID),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }
}
