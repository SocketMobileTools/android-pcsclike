/**
 * Copyright (c) 2018-2019 SpringCard - www.springcard.com
 * All right reserved
 * This software is covered by the SpringCard SDK License Agreement - see LICENSE.txt
 */

package com.springcard.pcsclike.communication

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.os.AsyncTask
import android.os.Looper
import android.util.Log
import com.springcard.pcsclike.*
import com.springcard.pcsclike.ccid.*
import com.springcard.pcsclike.utils.toHexString
import kotlin.concurrent.thread
import kotlin.experimental.and
import kotlin.experimental.inv


internal enum class State{
    Disconnected,
    Connecting,
    DiscoveringGatt,
    ReadingInformation,
    SubscribingNotifications,
    ReadingSlotsName,
    Authenticate,
    ConnectingToCard,
    Idle,
    Sleeping,
    ReadingPowerInfo,
    WritingCmdAndWaitingResp,
    Disconnecting
}

internal sealed class ActionEvent

internal sealed class Action : ActionEvent() {
    class Create(val ctx: Context) : Action()
    class Writing(val command: CcidCommand) : Action()
    class Authenticate : Action()
    class Disconnect : Action()
    class ReadPowerInfo : Action()
    class WakeUp: Action()
}

internal sealed class Event: ActionEvent() {
    class Connected : Event()
    class ServicesDiscovered(val status: Int) : Event()
    class DescriptorWritten(val descriptor: BluetoothGattDescriptor, val status: Int) : Event()
    class CharacteristicChanged(val characteristic: BluetoothGattCharacteristic) : Event()
    class CharacteristicWritten(val characteristic: BluetoothGattCharacteristic, val status: Int) : Event()
    class CharacteristicRead(val characteristic: BluetoothGattCharacteristic, val status: Int) : Event()
    class OnUsbInterrupt(val data: ByteArray) : Event()
    class OnUsbDataIn(val data: ByteArray) : Event()
    class Disconnected : Event()

}


internal abstract class CommunicationLayer(userCallbacks: SCardReaderListCallback, private var scardReaderList : SCardReaderList) {

    private val TAG = this::class.java.simpleName
    @Volatile protected var currentState = State.Disconnected
        get() {
            synchronized(field) {
                Log.d(TAG, "currentState = ${field.name}")
                return field
            }
        }
        set(value) {
            synchronized(field) {
                Log.d(TAG, "New currentState = ${value.name}")
                field = value
            }
        }
    internal lateinit var context: Context


    protected var indexSlots: Int = 0
    protected var listReadersToConnect = mutableListOf<SCardReader>()
    protected val callbacks = SynchronizedSCardReaderListCallback(userCallbacks, scardReaderList)

    abstract fun process(actionEvent: ActionEvent)

    /* Post error callbacks */

    internal fun postReaderListError(code : SCardError.ErrorCodes, detail: String, isFatal: Boolean = true) {
        Log.e(TAG, "Error readerList: ${code.name}, $detail")

        scardReaderList.postCallback({
            /* If the ScardReaderList has not been created yet --> null */
            if(scardReaderList.isAlreadyCreated) {
                callbacks.onReaderListError(scardReaderList, SCardError(code, detail, isFatal))
            }
            else {
                callbacks.onReaderListError(null, SCardError(code, detail, isFatal))
            }
        }, true)

        /* irrecoverable error --> close */
        if (isFatal) {
            process(Action.Disconnect())
            /* If an error happened while creating the device */
            if(!scardReaderList.isAlreadyCreated) {
                /* Remove it from the list of device known because we are not sure of anything about this one */
                scardReaderList.isCorrectlyKnown = false
            }
        }
    }

    internal fun postCardOrReaderError(code : SCardError.ErrorCodes, detail: String, reader: SCardReader) {
        Log.e(TAG, "Error reader or card: ${code.name}, $detail")
        scardReaderList.postCallback({ callbacks.onReaderOrCardError(reader,
            SCardError(code, detail)
        ) })
    }
    internal fun postCardOrReaderError(error: SCardError, reader: SCardReader) {
        Log.e(TAG, "Error reader or card: ${error.code.name}, ${error.detail}")
        scardReaderList.postCallback({ callbacks.onReaderOrCardError(reader, error) })
    }


    internal fun mayPostReaderListCreated() {

       /* We we still are in the create() */
        if(!scardReaderList.isAlreadyCreated) {

            /* Check if there are some slot to connect*/
            processNextSlotConnection(false)

            /* If there are no slot do connect */
            if(currentState == State.Idle) {

                /* Post callback and set variable only while creating the object */
                scardReaderList.isCorrectlyKnown = true
                scardReaderList.isAlreadyCreated = true

                val uniqueId = SCardReaderList.getDeviceUniqueId(scardReaderList.layerDevice)
                SCardReaderList.knownSCardReaderList[uniqueId] = scardReaderList.constants
                SCardReaderList.connectedScardReaderList.add(uniqueId)

                /* Retrieve readers name */
                scardReaderList.constants.slotsName.clear()
                for (i in 0 until scardReaderList.slotCount) {
                    scardReaderList.constants.slotsName.add(scardReaderList.readers[i].name)
                }

                scardReaderList.postCallback({ callbacks.onReaderListCreated(scardReaderList) }, true)
            }
        }
    }

    protected fun interpretSlotsStatus(data: ByteArray, isNotification:Boolean ) {

        /* If reader is being created, do not post callbacks neither return to Idle state */

        if(data.isEmpty()) {
            postReaderListError(
                SCardError.ErrorCodes.PROTOCOL_ERROR,
                "Error, interpretSlotsStatus: array is empty")
            return
        }

        /* If msb is set the device is gone to sleep, otherwise it is awake */
        val isSleeping = data[0] and LOW_POWER_NOTIFICATION == LOW_POWER_NOTIFICATION

        /* Product waking-up */
        if(scardReaderList.isSleeping && !isSleeping) {
            /* Set var before sending callback */
            scardReaderList.isSleeping = isSleeping
            /* Post callback, but it's not a response, so we must not unlock unnecessarily */
            scardReaderList.postCallback({ callbacks.onReaderListState(scardReaderList, isSleeping)}, scardReaderList.isAlreadyCreated, false)
        }
        /* Device going to sleep */
        else if(!scardReaderList.isSleeping && isSleeping) {
            /* Set var before sending callback */
            scardReaderList.isSleeping = isSleeping
            /* Post callback, but it's not a response, so we must not unlock unnecessarily */
            scardReaderList.postCallback({ callbacks.onReaderListState(scardReaderList, isSleeping) }, scardReaderList.isAlreadyCreated, false)
        }
        else if (scardReaderList.isSleeping && isSleeping) {
            Log.i(TAG, "Device is still sleeping...")
        }
        else if(!scardReaderList.isSleeping && !isSleeping) {
            Log.i(TAG, "Device is still awake...")
        }

        /* Update scardDevice state */
        scardReaderList.isSleeping = isSleeping

        /* If device is awake -> Interpret slot status */
        if(!isSleeping) {

            val slotCount = data[0] and LOW_POWER_NOTIFICATION.inv()

            /* Is slot count  matching nb of bytes*/
            if (slotCount > 4 * (data.size - 1)) {
                postReaderListError(
                    SCardError.ErrorCodes.PROTOCOL_ERROR,
                    "Error, too much slot ($slotCount) for ${data.size - 1} bytes"
                )
                return
            }

            /* Is slot count matching nb of readers in scardReaderList obj */
            if (slotCount.toInt() != scardReaderList.readers.size) {
                postReaderListError(
                    SCardError.ErrorCodes.PROTOCOL_ERROR,
                    "Error, slotCount in frame ($slotCount) does not match slotCount in scardReaderList (${scardReaderList.readers.size})"
                )
                return
            }

            for (i in 1 until data.size) {
                for (j in 0..3) {
                    val slotNumber = (i - 1) * 4 + j
                    if (slotNumber < slotCount) {

                        val slotStatus = (data[i].toInt() shr j * 2) and 0x03
                        Log.i(TAG, "Slot $slotNumber")

                        val slot = scardReaderList.readers[slotNumber]

                        /* Update SCardReadList slot status */
                        slot.cardPresent =
                            !(slotStatus == SCardReader.SlotStatus.Absent.code || slotStatus == SCardReader.SlotStatus.Removed.code)

                        /* If card is not present, it can not be powered */
                        if (!slot.cardPresent) {
                            slot.cardConnected = false
                            slot.channel.atr = ByteArray(0)
                        }

                        when (slotStatus) {
                            SCardReader.SlotStatus.Absent.code -> Log.i(
                                TAG,
                                "card absent, no change since last notification"
                            )
                            SCardReader.SlotStatus.Present.code -> Log.i(
                                TAG,
                                "card present, no change since last notification"
                            )
                            SCardReader.SlotStatus.Removed.code -> Log.i(TAG, "card removed notification")
                            SCardReader.SlotStatus.Inserted.code -> Log.i(TAG, "card inserted notification")
                            else -> {
                                Log.w(TAG, "Impossible value : $slotStatus")
                            }
                        }

                       /* Card considered remove if we read the CCID status and card is absent */
                        val cardRemoved =
                            (slotStatus == SCardReader.SlotStatus.Removed.code) || (!isNotification && !slot.cardPresent)
                        /* Card considered inserted if we read the CCID status and card is present */
                        val cardInserted =
                            (slotStatus == SCardReader.SlotStatus.Inserted.code) || (!isNotification && slot.cardPresent)

                        /* Update list of slots to connect (if there is no card error) */
                        if (cardRemoved && listReadersToConnect.contains(slot)) {
                            Log.d(TAG, "Card gone on slot ${slot.index}, removing slot from listReadersToConnect")
                            listReadersToConnect.remove(slot)
                        } else if (cardInserted && slot.channel.atr.isEmpty() && !listReadersToConnect.contains(slot) /*&& !slot.cardError*/) { // TODO CRA sse if cardError is useful
                            Log.d(TAG, "Card arrived on slot ${slot.index}, adding slot to listReadersToConnect")
                            listReadersToConnect.add(slot)
                        }

                        /* Send callback only if card removed, when the card is inserted */
                        /* the callback will be send after the connection to the card  */
                        if (cardRemoved) {
                            /* Reset cardError flag */
                            slot.cardError = false
                            /* Post callback, but it's not a response, so we must not unlock unnecessarily */
                            scardReaderList.postCallback({
                                callbacks.onReaderStatus(
                                    slot,
                                    slot.cardPresent,
                                    slot.cardConnected)
                            }, scardReaderList.isAlreadyCreated, false)
                        }

                        /* This line may be optional since slot is a reference on scardReaderList.readers[slotNumber] and not a copy */
                        scardReaderList.readers[slotNumber] = slot
                    }
                }
            }
        }
        else {
            Log.i(TAG,"ScardReaderList is sleeping, do not read CCID status data, consider all cards not connected and not powered")
            for(i in 0 until  scardReaderList.readers.size) {
                scardReaderList.readers[i].cardPowered = false
                scardReaderList.readers[i].cardConnected = false
                scardReaderList.readers[i].channel.atr = ByteArray(0)
            }
            currentState = State.Sleeping
        }
    }


    /**
     * Interpret slot status byte and update cardPresent and cardPowered
     *
     * @param slotStatus Byte
     * @param slot SCardReader
     */
    protected fun interpretSlotsStatusInCcidHeader(slotStatus: Byte, slot: SCardReader) {

        val cardStatus = slotStatus.toInt() and 0b00000011

        when (cardStatus) {
            0b00 -> {
                Log.i(TAG, "A Card is present and active (powered ON)")
                slot.cardPresent = true
                slot.cardPowered = true
            }
            0b01 -> {
                Log.i(TAG, "A Card is present and inactive (powered OFF or hardware error)")
                slot.cardPresent = true
                slot.cardPowered = false
            }
            0b10  -> {
                Log.i(TAG, "No card present (slot is empty)")
                slot.cardPresent = false
                slot.cardPowered = false
            }
            0b11  -> {
                Log.i(TAG, "Reserved for future use")
            }
            else -> {
                Log.w(TAG, "Impossible value for cardStatus : $slotStatus")
            }
        }
    }

    /**
     * Interpret slot error byte, and send error callback if necessary
     *
     * @param slotError Byte
     * @param slotStatus Byte
     * @param slot SCardReader
     * @return null if there is no error, [SCardError] otherwise
     */
    protected fun interpretSlotsErrorInCcidHeader(slotError: Byte, slotStatus: Byte, slot: SCardReader): SCardError? {

        val commandStatus = (slotStatus.toInt() and 0b11000000) shr 6

        when (commandStatus) {
            0b00 -> {
                Log.i(TAG, "Command processed without error")
                return null
            }
            0b01 -> {
                Log.i(TAG, "Command failed (error code is provided in the SlotError field)")
            }
            else -> {
                Log.w(TAG, "Impossible value for commandStatus : $slotStatus")
            }
        }

        Log.e(TAG, "Error in CCID Header: 0x${String.format("%02X", slotError)}")

        var errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
        var detail = ""

        when (slotError) {
            SCardReader.SlotError.CMD_ABORTED.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "The PC has sent an ABORT command"
            }
            SCardReader.SlotError.ICC_MUTE.code -> {
                errorCode = SCardError.ErrorCodes.CARD_MUTE
                detail = "CCID slot error: Time out in Card communication"
            }
            SCardReader.SlotError.XFR_PARITY_ERROR.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "Parity error in Card communication"
            }
            SCardReader.SlotError.XFR_OVERRUN.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "Overrun error in Card communication"
            }
            SCardReader.SlotError.HW_ERROR.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "Hardware error on Card side (over-current?)"
            }
            SCardReader.SlotError.BAD_ATR_TS.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "Invalid ATR format"
            }
            SCardReader.SlotError.BAD_ATR_TCK.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "Invalid ATR checksum"
            }
            SCardReader.SlotError.ICC_PROTOCOL_NOT_SUPPORTED.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "Card's protocol is not supported"
            }
            SCardReader.SlotError.ICC_CLASS_NOT_SUPPORTED.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "Card's power class is not supported"
            }
            SCardReader.SlotError.PROCEDURE_BYTE_CONFLICT.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "Error in T=0 protocol"
            }
            SCardReader.SlotError.DEACTIVATED_PROTOCOL.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "Specified protocol is not allowed"
            }
            SCardReader.SlotError.BUSY_WITH_AUTO_SEQUENCE.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "RDR is currently busy activating a Card"
            }
            SCardReader.SlotError.CMD_SLOT_BUSY.code -> {
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "RDR is already running a command)}"
            }
            SCardReader.SlotError.CMD_NOT_SUPPORTED.code -> {
                // TODO CRA do something in springcore fw ??
                return null
            }
            else -> {
                Log.w(TAG, "CCID Error code not handled")
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "CCID slot error: 0x${String.format("%02X", slotError)}"
            }
        }

        slot.cardError = true

        return SCardError(errorCode, detail)
    }


    protected fun analyseResponse(data: ByteArray) {
        /* Put data in ccid frame */
        val ccidResponse = scardReaderList.ccidHandler.getCcidResponse(data)
        val slot = scardReaderList.readers[ccidResponse.slotNumber.toInt()]

        /* Update slot status (present, powered) */
        interpretSlotsStatusInCcidHeader(ccidResponse.slotStatus, slot)

        /* Check slot error */
        val error = interpretSlotsErrorInCcidHeader(ccidResponse.slotError, ccidResponse.slotStatus, slot)
        if(error != null) {
            Log.d(TAG, "Error, do not process CCID packet, returning to Idle state")
            postCardOrReaderError(error, slot)
            return
        }

        currentState = State.Idle
        Log.d(TAG, "Frame complete, length = ${ccidResponse.length}")
        when {
            ccidResponse.code == CcidResponse.ResponseCode.RDR_To_PC_Escape.value -> when (scardReaderList.ccidHandler.commandSend) {
                CcidCommand.CommandCode.PC_To_RDR_Escape -> scardReaderList.postCallback({
                    callbacks.onControlResponse(
                        scardReaderList,
                        ccidResponse.payload
                    )
                })
                else -> postReaderListError(SCardError.ErrorCodes.DIALOG_ERROR, "Unexpected CCID response (${String.format("%02X", ccidResponse.code)}) for command : ${scardReaderList.ccidHandler.commandSend}")
            }
            ccidResponse.code == CcidResponse.ResponseCode.RDR_To_PC_DataBlock.value -> when (scardReaderList.ccidHandler.commandSend) {
                CcidCommand.CommandCode.PC_To_RDR_XfrBlock -> {
                    if (ccidResponse.slotNumber > scardReaderList.readers.size) {
                        postReaderListError(
                            SCardError.ErrorCodes.PROTOCOL_ERROR,
                            "Error, slot number specified (${ccidResponse.slotNumber}) greater than maximum slot (${scardReaderList.readers.size - 1}), maybe the packet is incorrect"
                        )
                    } else {

                        currentState = State.Idle
                        scardReaderList.postCallback({
                            callbacks.onTransmitResponse(
                                slot.channel,
                                ccidResponse.payload
                            )
                        })
                    }
                }
                CcidCommand.CommandCode.PC_To_RDR_IccPowerOn -> {
                    /* save ATR */
                    slot.channel.atr = ccidResponse.payload
                    /* set cardConnected flag */
                    slot.cardConnected = true
                    /* Change state */
                    currentState = State.Idle
                    /* Call callback */
                    scardReaderList.postCallback({ callbacks.onCardConnected(slot.channel) })
                }
                else -> postReaderListError(SCardError.ErrorCodes.DIALOG_ERROR, "Unexpected CCID response (${String.format("%02X", ccidResponse.code)}) for command : ${scardReaderList.ccidHandler.commandSend}")
            }
            ccidResponse.code == CcidResponse.ResponseCode.RDR_To_PC_SlotStatus.value -> when (scardReaderList.ccidHandler.commandSend) {
                CcidCommand.CommandCode.PC_To_RDR_GetSlotStatus -> {
                    /* Do nothing */
                    Log.d(TAG, "Reader Status, Cool! ...but useless")

                    /* Update slot concerned */
                    interpretSlotsStatusInCcidHeader(ccidResponse.slotStatus, slot)
                }
                CcidCommand.CommandCode.PC_To_RDR_IccPowerOff -> {
                    slot.cardConnected = false
                    slot.channel.atr = ByteArray(0)
                    scardReaderList.postCallback({ callbacks.onCardDisconnected(slot.channel) })
                }
                CcidCommand.CommandCode.PC_To_RDR_XfrBlock -> {
                    if(slot.cardPresent && !slot.cardPowered) {
                        val scardError = SCardError(
                            SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR,
                            "Transmit invoked, but card not powered"
                        )
                        scardReaderList.postCallback({ callbacks.onReaderOrCardError(slot, scardError) })
                    }
                    // TODO CRA else ...
                }
                CcidCommand.CommandCode.PC_To_RDR_IccPowerOn -> {
                    val channel = slot.channel
                    slot.channel.atr = ccidResponse.payload
                    slot.cardConnected = true
                    scardReaderList.postCallback({ callbacks.onCardConnected(channel) })
                    // TODO onReaderOrCardError
                }
                else -> postReaderListError(SCardError.ErrorCodes.DIALOG_ERROR, "Unexpected CCID response (${String.format("%02X", ccidResponse.code)}) for command : ${scardReaderList.ccidHandler.commandSend}")
            }
            else -> postReaderListError(SCardError.ErrorCodes.DIALOG_ERROR, "Unknown CCID response (${String.format("%02X", ccidResponse.code)}) for command : ${scardReaderList.ccidHandler.commandSend}")
        }
    }

    /* If the lock is already active (internal state like autoconnect to card while creating) , do not try to relock */
    internal fun processNextSlotConnection(useLock: Boolean = true) {
        /* If there are one card present on one or more slot --> go to state ConnectingToCard */
        if(listReadersToConnect.size > 0) {
            Log.d(TAG, "There is ${listReadersToConnect.size} card(s) to connect")
            currentState = State.ConnectingToCard
            /* Call explicitly ccidHandler.scardConnect() instead of reader.scardConnect() */
            /* Because if the card is present and powered (in USB) the command will not be send */
            /* In USB the card is auto powered if present and it's not the case in BLE */
            scardReaderList.processAction(
                Action.Writing(
                    scardReaderList.ccidHandler.scardConnect(
                        listReadersToConnect[0].index.toByte()
                    )
                ),
                useLock
            )
        }
        /* Otherwise go to idle state */
        else {
            Log.d(TAG, "There is no cards to connect")
            currentState = State.Idle
        }
    }

    protected fun getVersionFromRevString(revString: String) {
        scardReaderList.constants.firmwareVersion = revString
        scardReaderList.constants.firmwareVersionMajor = revString.split("-")[0].split(".")[0].toInt()
        scardReaderList.constants.firmwareVersionMinor = revString.split("-")[0].split(".")[1].toInt()
        scardReaderList.constants.firmwareVersionBuild = revString.split("-")[1].toInt()
    }

    protected fun PC_to_RDR(cmd: CcidCommand) {
        /* Update sqn, save it and cipher */
        val updatedCcidBuffer = scardReaderList.ccidHandler.updateCcidCommand(cmd)
        Log.d(TAG, "Writing ${cmd.raw.toHexString()} in PC_to_RDR")
        writePcToRdr(updatedCcidBuffer)
    }

    protected abstract fun writePcToRdr(buffer: ByteArray)

    companion object {
        const val LOW_POWER_NOTIFICATION: Byte = 0x80.toByte()
    }
}