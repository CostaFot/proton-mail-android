/*
 * Copyright (c) 2020 Proton Technologies AG
 * 
 * This file is part of ProtonMail.
 * 
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.contacts.details.edit

import android.annotation.SuppressLint
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.text.TextUtils
import ch.protonmail.android.api.models.room.contacts.ContactEmail
import ch.protonmail.android.api.models.room.contacts.ContactLabel
import ch.protonmail.android.api.rx.ThreadSchedulers
import ch.protonmail.android.api.utils.ParseUtils
import ch.protonmail.android.contacts.details.ContactDetailsViewModel
import ch.protonmail.android.core.UserManager
import ch.protonmail.android.utils.Event
import ch.protonmail.android.views.models.LocalContact
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.property.*
import io.reactivex.Observable
import kotlinx.coroutines.*
import java.util.*
import javax.inject.Inject
import kotlin.collections.HashMap

// region constants
const val FLOW_NEW_CONTACT = 1
const val FLOW_EDIT_CONTACT = 2
const val FLOW_CONVERT_CONTACT = 3

const val EXTRA_CONTACT = "extra_contact"
const val EXTRA_FLOW = "extra_flow"
const val EXTRA_NAME = "extra_name"
const val EXTRA_EMAIL = "extra_email"
const val EXTRA_CONTACT_VCARD_TYPE0 = "extra_vcard_type0"
const val EXTRA_CONTACT_VCARD_TYPE2 = "extra_vcard_type2"
const val EXTRA_CONTACT_VCARD_TYPE3 = "extra_vcard_type3"
const val EXTRA_LOCAL_CONTACT = "extra_local_contact"

private const val VCARD_PROD_ID = "-//ProtonMail//ProtonMail for Android vCard 1.0.0//EN"
// endregion

/**
 * Created by kadrikj on 9/26/18.
 */

class EditContactDetailsViewModel @Inject constructor(private val editContactDetailsRepository: EditContactDetailsRepository,
                                                      private val userManager: UserManager) : ContactDetailsViewModel(editContactDetailsRepository) {

    // region events
    private val _cleanUpComplete: MutableLiveData<Event<Boolean>> = MutableLiveData()
    private val _setupNewContactFlow: MutableLiveData<String> = MutableLiveData()
    private val _setupEditContactFlow: MutableLiveData<EditContactCardsHolder> = MutableLiveData()
    private val _setupConvertContactFlow: MutableLiveData<Unit> = MutableLiveData()
    private val _freeUserEvent: MutableLiveData<Unit> = MutableLiveData()
    val cleanUpComplete: LiveData<Event<Boolean>>
        get() = _cleanUpComplete
    val setupNewContactFlow: LiveData<String>
        get() = _setupNewContactFlow
    val setupEditContactFlow: LiveData<EditContactCardsHolder>
        get() = _setupEditContactFlow
    val setupConvertContactFlow: LiveData<Unit>
        get() = _setupConvertContactFlow
    val freeUserEvent: LiveData<Unit>
        get() = _freeUserEvent
    // endregion

    // region data
    private lateinit var _contactId: String
    private var _localContact: LocalContact? = null
    private lateinit var _email: String
    private var _flow: Int = 0
    private var _changed: Boolean = false
    private lateinit var _vCardPhoneUIOptions: List<String>
    private lateinit var _vCardPhoneOptions: List<String>
    private lateinit var _vCardEmailUIOptions: List<String>
    private lateinit var _vCardEmailOptions: List<String>
    private lateinit var _vCardAddressUIOptions: List<String>
    private lateinit var _vCardAddressOptions: List<String>
    private lateinit var _vCardOtherOptions: List<String>

    private lateinit var _vCardType0: VCard
    private lateinit var _vCardType2: VCard
    private lateinit var _vCardType3: VCard
    private lateinit var _uid: String
    private lateinit var _productId: ProductId
    private var _vCardCustomProperties: List<RawProperty>? = null
    private lateinit var _vCardSigned: VCard
    private lateinit var _vCardEncrypted: VCard
    private lateinit var _mapEmailGroupsIds: HashMap<ContactEmail, List<ContactLabel>>
    // endregion
    // region setup
    fun setup(flow: Int, contactId: String, localContact: LocalContact?, email: String,
              vCardPhoneUIOptions: List<String>,
              vCardPhoneOptions: List<String>,
              vCardEmailUIOptions: List<String>,
              vCardEmailOptions: List<String>,
              vCardAddressUIOptions: List<String>,
              vCardAddressOptions: List<String>,
              vCardOtherOptions: List<String>,
              vCardStringType0: String?,
              vCardStringType2: String?,
              vCardStringType3: String?) {
        _flow = flow
        _contactId = contactId
        _localContact = localContact
        _email = email
        _vCardPhoneUIOptions = vCardPhoneUIOptions
        _vCardPhoneOptions = vCardPhoneOptions
        _vCardEmailUIOptions = vCardEmailUIOptions
        _vCardEmailOptions = vCardEmailOptions
        _vCardAddressUIOptions = vCardAddressUIOptions
        _vCardAddressOptions = vCardAddressOptions
        _vCardOtherOptions = vCardOtherOptions
        setupVCards(vCardStringType0, vCardStringType2, vCardStringType3)
        val isPaid = userManager.user?.isPaidUser ?: false
        if (!isPaid) {
            _freeUserEvent.postValue(null)
        }
        postSetupFlowEvent()
        if (_flow == FLOW_EDIT_CONTACT) {
            fetchContactGroupsAndContactEmails(_contactId)
        }
    }

    private fun postSetupFlowEvent() {
        when (_flow) {
            FLOW_NEW_CONTACT -> _setupNewContactFlow.postValue(_email)
            FLOW_EDIT_CONTACT -> _setupEditContactFlow.postValue(EditContactCardsHolder(_vCardType0, _vCardType2, _vCardType3))
            FLOW_CONVERT_CONTACT -> _setupConvertContactFlow.postValue(null)
        }
    }

    private fun setupVCards(vCardStringType0: String?, vCardStringType2: String?, vCardStringType3: String?) {
        var vCard0: VCard? = null
        if (!TextUtils.isEmpty(vCardStringType0)) {
            vCard0 = Ezvcard.parse(vCardStringType0).first()
        }
        _vCardType0 = vCard0 ?: VCard()
        var vCard2: VCard? = null
        if (!TextUtils.isEmpty(vCardStringType2)) {
            vCard2 = Ezvcard.parse(vCardStringType2).first()
        }
        if (vCard2 == null) {
            _vCardType2 = VCard()
            if (_vCardType0.uid != null) {
                _uid = _vCardType0.uid.value
            }
            if (!::_uid.isInitialized || TextUtils.isEmpty(_uid)) {
                _uid = "proton-android-" + UUID.randomUUID().toString()
            }
        } else {
            _vCardType2 = vCard2
            _uid = _vCardType2.uid.value
        }
        var vCard3: VCard? = null
        if (!TextUtils.isEmpty(vCardStringType3)) {
            vCard3 = Ezvcard.parse(vCardStringType3).first()
        }
        _vCardType3 = vCard3 ?: VCard()
        // getting prod id
        val prodId = _vCardType3.productId
        _productId = if (prodId == null || TextUtils.isEmpty(prodId.value)) {
            ProductId(VCARD_PROD_ID)
        } else {
            prodId
        }
        // getting custom properties
        _vCardCustomProperties = _vCardType3.extendedProperties
    }
    // endregion
    // region default options
    val defaultEmailOption: String
        get() = _vCardEmailOptions[0]
    val defaultEmailUIOption: String
        get() = _vCardEmailUIOptions[0]
    val defaultPhoneOption: String
        get() = _vCardPhoneOptions[0]
    val defaultPhoneUIOption: String
        get() = _vCardPhoneUIOptions[0]
    val defaultAddressOption: String
        get() = _vCardAddressOptions[0]
    val defaultAddressUIOption: String
        get() = _vCardAddressUIOptions[0]
    val defaultOtherOption: String
        get() = _vCardOtherOptions[0]
    // endregion
    //region options lists
    val emailOptions: List<String>
        get() = _vCardEmailOptions
    val emailUIOptions: List<String>
        get() = _vCardEmailUIOptions
    val phoneOptions: List<String>
        get() = _vCardPhoneOptions
    val phoneUIOptions: List<String>
        get() = _vCardPhoneUIOptions
    val addressOptions: List<String>
        get() = _vCardAddressOptions
    val addressUIOptions: List<String>
        get() = _vCardAddressUIOptions
    val otherOptions: List<String>
        get() = _vCardOtherOptions
    // endregion
    // region others
    val localContact: LocalContact?
        get() = _localContact
    val contactId: String
        get() = _contactId

    fun isConvertContactFlow(): Boolean = _flow == FLOW_CONVERT_CONTACT
    // endregion
    // region card properties
    fun getPhotos(): List<Photo>? = _vCardType3.photos
    fun getOrganizations(): List<Organization>? = _vCardType3.organizations
    fun getTitles(): List<Title>? = _vCardType3.titles
    fun getNicknames(): List<Nickname>? = _vCardType3.nicknames
    fun getBirthdays(): List<Birthday>? = _vCardType3.birthdays
    fun getAnniversaries(): List<Anniversary>? = _vCardType3.anniversaries
    fun getRoles(): List<Role>? = _vCardType3.roles
    fun getUrls(): List<Url>? = _vCardType3.urls
    fun getGender(): Gender? = _vCardType3.gender
    fun getExtendedPropertiesType3(): List<RawProperty>? = _vCardType3.extendedProperties
    fun getNotes(): List<Note>? = _vCardType3.notes
    fun getExtendedPropertiesType2(): List<RawProperty> = _vCardType2.extendedProperties
    fun getKeysType2(): List<Key> = _vCardType2.keys
    // endregion

    fun buildEncryptedCard(): VCard {
        val vCardEncrypted = VCard()
        vCardEncrypted.version = VCardVersion.V4_0
        _vCardEncrypted = vCardEncrypted
        return vCardEncrypted
    }

    fun buildSignedCard(contactName: String): VCard {
        val vCardSigned = VCard()
        vCardSigned.setFormattedName(contactName)
        vCardSigned.uid = Uid(_uid)
        vCardSigned.version = VCardVersion.V4_0
        vCardSigned.setProductId(_productId.value)
        _vCardSigned = vCardSigned
        return vCardSigned
    }

    fun save(emailsToBeRemoved: List<String>, contactName: String, _emails: List<ContactEmail>) {
        val emails = validateEmails(_emails)
        val emailFromVCard = ArrayList<Email>()
        emailFromVCard.addAll(_vCardSigned.emails)
        _vCardSigned.emails.removeAll(emailFromVCard)
        _vCardSigned.emails.addAll(emailFromVCard.distinctBy { it.value })
        GlobalScope.launch(Dispatchers.Default, CoroutineStart.DEFAULT) {
            withContext(Dispatchers.Default) {
                emailsToBeRemoved.forEach {
                    editContactDetailsRepository.clearEmail(it)
                }
            }
        }
        when (_flow) {
            FLOW_EDIT_CONTACT -> {
                editContactDetailsRepository.updateContact(_contactId, contactName, emails, _vCardEncrypted, _vCardSigned, _mapEmailGroupsIds)
            }
            FLOW_NEW_CONTACT -> editContactDetailsRepository.createContact(contactName, emails, _vCardEncrypted, _vCardSigned)
            FLOW_CONVERT_CONTACT -> editContactDetailsRepository.convertContact(contactName, emails, _vCardEncrypted, _vCardSigned)
        }
    }

    fun setChanged() {
        _changed = true
    }

    /**
     * Should inform the view that the user want's to leave the screen, so that the view is able to save
     * state or do whatever needed for cleanup.
     */
    fun onBackPressed() {
        _cleanUpComplete.postValue(Event(_changed))
        _changed = false
    }

    @SuppressLint("CheckResult")
    fun fetchContactGroupsForEmails() {
        Observable.just(allContactEmails)
            .flatMap {
                _mapEmailGroupsIds = HashMap()
                Observable.fromIterable(it)
                    .map { email ->
                        val groupsForThisEmail = allContactGroups.filter { group ->
                            email.labelIds?.contains(group.ID) ?: false
                        }
                        _mapEmailGroupsIds[email] = groupsForThisEmail
                    }
            }.subscribeOn(ThreadSchedulers.main())
            .observeOn(ThreadSchedulers.main())
            .subscribe({
                           val size = _mapEmailGroupsIds.size
                       }, {
                           ParseUtils.doOnError(it)
                       })
    }

    private fun validateEmails(emails: List<ContactEmail>): List<ContactEmail> {
        return emails.distinctBy { it.email }
    }

    class EditContactCardsHolder(val vCardType0: VCard, val vCardType2: VCard, val vCardType3: VCard)
}