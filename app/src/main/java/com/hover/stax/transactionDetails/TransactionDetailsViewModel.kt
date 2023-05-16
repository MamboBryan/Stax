/*
 * Copyright 2022 Stax
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hover.stax.transactionDetails

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.hover.sdk.actions.HoverAction
import com.hover.sdk.api.Hover.getSMSMessageByUUID
import com.hover.sdk.transactions.Transaction
import com.hover.stax.data.accounts.AccountRepository
import com.hover.stax.data.actions.ActionRepo
import com.hover.stax.data.merchant.MerchantRepo
import com.hover.stax.data.parser.ParserRepo
import com.hover.stax.data.transactions.TransactionRepo
import com.hover.stax.database.models.Account
import com.hover.stax.database.models.Merchant
import com.hover.stax.database.models.StaxContact
import com.hover.stax.database.models.StaxTransaction
import com.hover.stax.database.models.UssdCallResponse
import com.hover.stax.database.repo.ContactRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber
import javax.inject.Inject
@HiltViewModel
class TransactionDetailsViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val repo: TransactionRepo,
    val actionRepo: ActionRepo,
    val contactRepo: ContactRepo,
    val accountRepo: AccountRepository,
    private val parserRepo: ParserRepo,
    private val merchantRepo: MerchantRepo
) : ViewModel() {

    val transaction = MutableLiveData<StaxTransaction>()
    var account: LiveData<Account> = MutableLiveData()
    var action: LiveData<HoverAction> = MutableLiveData()

    var contact: LiveData<StaxContact> = MutableLiveData()
    var merchant: LiveData<Merchant> = MutableLiveData()

    var hoverTransaction = MutableLiveData<Transaction>()
    val messages = MediatorLiveData<List<UssdCallResponse>>()
    var sms: LiveData<List<UssdCallResponse>> = MutableLiveData()
    val isExpectingSMS: MediatorLiveData<Boolean> =
        MediatorLiveData<Boolean>().also { it.value = false }
    var bonusAmt: MediatorLiveData<Int> = MediatorLiveData()

    init {
        account = transaction.switchMap(this::getLiveAccount)
        action = transaction.switchMap(this::getLiveAction)
        contact = transaction.switchMap(this::getLiveContact)
//        merchant = transaction.switchMap { it?.let { getLiveMerchant(it) } } TODO - FIX ME

        messages.apply {
            addSource(transaction) { loadMessages(it) }
            addSource(action) { loadMessages(it) }
        }

//        sms = transaction.map { it?.let { loadSms(it) } } TODO - FIX ME
        isExpectingSMS.addSource(transaction, this::setExpectingSMS)
    }

    private fun getLiveAccount(txn: StaxTransaction?): LiveData<Account>? = if (txn != null)
        txn.accountId?.let { accountRepo.getLiveAccount(it) }
    else null

    private fun getLiveAction(txn: StaxTransaction?): LiveData<HoverAction>? = if (txn != null)
        actionRepo.getLiveAction(txn.action_id)
    else null

    private fun getLiveContact(txn: StaxTransaction?): LiveData<StaxContact>? = if (txn != null)
        contactRepo.getLiveContact(txn.counterparty_id)
    else null

    private fun getLiveMerchant(txn: StaxTransaction?): LiveData<Merchant?>? =
        if (txn != null && txn.transaction_type == HoverAction.MERCHANT && txn.counterpartyNo != null)
            merchantRepo.getLiveMatching(txn.counterpartyNo!!, txn.channel_id)
        else null

    fun setTransaction(uuid: String) = viewModelScope.launch(Dispatchers.IO) {
        repo.getTransactionAsync(uuid).collect { transaction.postValue(it) }
    }

    private fun loadMessages(txn: StaxTransaction?) {
        if (action.value != null && txn != null) loadMessages(txn, action.value!!)
    }

    private fun loadMessages(a: HoverAction?) {
        if (transaction.value != null && a != null) loadMessages(transaction.value!!, a)
    }

    private fun loadMessages(txn: StaxTransaction, a: HoverAction) {
        val t = repo.loadFromHover(txn.uuid)
        t?.let { transaction ->
            hoverTransaction.value = transaction
            messages.value = UssdCallResponse.generateConvo(transaction, a)
        }
    }

    private fun loadSms(txn: StaxTransaction): List<UssdCallResponse> {
        val t = repo.loadFromHover(txn.uuid)
        t?.let { transaction ->
            hoverTransaction.value = transaction
        }

        return t?.let { transaction ->
            val smsArr =
                if (transaction.smsHits != null && transaction.smsHits.length() > 0) transaction.smsHits else transaction.smsMisses
            generateSmsConvo(smsArr)
        }?.toList() ?: emptyList()
    }

    private fun generateSmsConvo(smsArr: JSONArray): ArrayList<UssdCallResponse> {
        val smses = ArrayList<UssdCallResponse>()
        for (i in 0 until smsArr.length()) {
            val sms = getSMSMessageByUUID(smsArr.optString(i), context)
            Timber.e(sms.uuid)
            smses.add(
                UssdCallResponse(
                    null,
                    sms.msg
                )
            )
        }
        return smses
    }

    fun wrapExtras(): HashMap<String, String> {
        val extras = HashMap<String, String>()
        if (transaction.value?.amount != null) extras[HoverAction.AMOUNT_KEY] =
            transaction.value!!.amount.toString()
        if (contact.value?.accountNumber != null) extras[HoverAction.PHONE_KEY] =
            contact.value!!.accountNumber
        if (contact.value?.accountNumber != null) extras[HoverAction.ACCOUNT_KEY] =
            contact.value!!.accountNumber
        if (transaction.value?.counterparty_id != null) extras[StaxContact.ID_KEY] =
            transaction.value!!.counterparty_id!!
        if (transaction.value?.note != null) extras[HoverAction.NOTE_KEY] =
            transaction.value!!.note!!
        Timber.e("Extras %s", extras.keys)
        return extras
    }

    private fun setExpectingSMS(transaction: StaxTransaction?) =
        viewModelScope.launch(Dispatchers.IO) {
            transaction?.let {
                val hasSMSParser = parserRepo.hasSMSParser(transaction.action_id)
                if (transaction.isPending) isExpectingSMS.postValue(hasSMSParser)
            }
        }
}