package com.sidephone.aviary.data

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

data class DeviceContact(val name: String, val number: String, val photoUri: String?)

/** Reads the phone's contacts (name + number) for the new-conversation picker. */
object Contacts {

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** All contacts with a phone number, one row per distinct number, sorted by name. */
    fun all(context: Context): List<DeviceContact> {
        if (!hasPermission(context)) return emptyList()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
        )
        val seen = HashSet<String>()
        val out = ArrayList<DeviceContact>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { c ->
                val iName = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val iNum = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val iPhoto = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val number = c.getString(iNum)?.trim() ?: continue
                    val key = number.filter { it.isDigit() }.takeLast(10)
                    if (key.isBlank() || !seen.add("$name|$key")) continue
                    out.add(DeviceContact(name, number, c.getString(iPhoto)))
                }
            }
        }
        return out
    }

    /** Contacts that have an email address (the address is carried in [DeviceContact.number]),
     *  one row per distinct email. Used to start an iMessage thread to an email handle. */
    fun emails(context: Context): List<DeviceContact> {
        if (!hasPermission(context)) return emptyList()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            ContactsContract.CommonDataKinds.Email.PHOTO_THUMBNAIL_URI,
        )
        val seen = HashSet<String>()
        val out = ArrayList<DeviceContact>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI, proj, null, null,
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { c ->
                val iName = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                val iAddr = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                val iPhoto = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.PHOTO_THUMBNAIL_URI)
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val email = c.getString(iAddr)?.trim() ?: continue
                    if (email.isBlank() || !seen.add(email.lowercase())) continue
                    out.add(DeviceContact(name, email, c.getString(iPhoto)))
                }
            }
        }
        return out
    }
}
