/**
 * Copyright (c) 2018-2019 SpringCard - www.springcard.com
 * All right reserved
 * This software is covered by the SpringCard SDK License Agreement - see LICENSE.txt
 */

package com.springcard.pcscblelib

import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.*
import kotlin.experimental.and


internal enum class State{
    Disconnected,
    Connecting,
    Connected,
    DiscoveringGatt,
    ReadingInformation,
    SubscribingNotifications,
    ReadingSlotsName,
    ConnectingToCard,
    Idle,
    ReadingPowerInfo,
    WritingCommand,
    WaitingResponse,
    Disconnecting
}


internal sealed class ActionEvent {
    class ActionConnect(val ctx: Context) : ActionEvent()
    class EventConnected : ActionEvent()
    class ActionCreate : ActionEvent()
    class EventServicesDiscovered(val status: Int) : ActionEvent()
    class EventDescriptorWrite(val descriptor: BluetoothGattDescriptor, val status: Int) : ActionEvent()
    class EventCharacteristicChanged(val characteristic: BluetoothGattCharacteristic) : ActionEvent()
    class EventCharacteristicWrite(val characteristic: BluetoothGattCharacteristic, val status: Int) : ActionEvent()
    class EventCharacteristicRead(val characteristic: BluetoothGattCharacteristic, val status: Int) : ActionEvent()
    class ActionWriting(val charUuid: UUID, val command: ByteArray) : ActionEvent()
    class ActionDisconnect : ActionEvent()
    class EventDisconnected : ActionEvent()
    class ActionReadPowerInfo : ActionEvent()
}


internal class BluetoothLeService(private var bluetoothDevice: BluetoothDevice, private var callbacks: SCardReaderListCallback, private var scardDevice : SCardReaderList) {

    private val TAG = this::class.java.simpleName

    private lateinit var mBluetoothGatt: BluetoothGatt
    private var currentState = State.Disconnected

    private val charUuidToRead = mutableListOf<UUID>(
        GattAttributesSpringCore.UUID_MODEL_NUMBER_STRING_CHAR,
        GattAttributesSpringCore.UUID_SERIAL_NUMBER_STRING_CHAR,
        GattAttributesSpringCore.UUID_FIRMWARE_REVISION_STRING_CHAR,
        GattAttributesSpringCore.UUID_HARDWARE_REVISION_STRING_CHAR,
        GattAttributesSpringCore.UUID_SOFTWARE_REVISION_STRING_CHAR,
        GattAttributesSpringCore.UUID_MANUFACTURER_NAME_STRING_CHAR,
        GattAttributesSpringCore.UUID_PNP_ID_CHAR)

    private val charUuidToReadPower = mutableListOf<UUID>(
        GattAttributesSpringCore.UUID_BATTERY_POWER_STATE_CHAR,
        GattAttributesSpringCore.UUID_BATTERY_LEVEL_CHAR)

    private var characteristicsToRead : MutableList<BluetoothGattCharacteristic> = mutableListOf<BluetoothGattCharacteristic>()
    private var characteristicsCanIndicate : MutableList<BluetoothGattCharacteristic> = mutableListOf<BluetoothGattCharacteristic>()
    private var characteristicsToReadPower : MutableList<BluetoothGattCharacteristic> = mutableListOf<BluetoothGattCharacteristic>()

    /* Various callback methods defined by the BLE API */
    private val mGattCallback: BluetoothGattCallback by lazy {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    process(ActionEvent.EventConnected())
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    process(ActionEvent.EventDisconnected())
                }
                // TODO CRA else ...
            }

            override// New services discovered
            fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {
                process(ActionEvent.EventServicesDiscovered(status))
            }

            override// Result of a characteristic read operation
            fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                process(ActionEvent.EventCharacteristicRead(characteristic, status))
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                process(ActionEvent.EventCharacteristicWrite(characteristic, status))
            }

            override// Characteristic notification
            fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                process(ActionEvent.EventCharacteristicChanged(characteristic))
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                process(ActionEvent.EventDescriptorWrite(descriptor, status))
            }
        }
    }


    /* Utilities methods */

    private fun connect(ctx: Context) {
        mBluetoothGatt = bluetoothDevice.connectGatt(ctx, false, mGattCallback)
    }

     private fun create() {
         mBluetoothGatt.discoverServices()
         Log.i(TAG, "Attempting to start service discovery")
    }


    private fun disconnect(): Boolean {

        Log.d(TAG, "Disconnect")

        mBluetoothGatt.disconnect()
        return true
    }



    private fun getCharacteristic(charUuid: UUID) : BluetoothGattCharacteristic? {
        return  mBluetoothGatt.getService(GattAttributesSpringCore.UUID_SPRINGCARD_CCID_SERVICE)?.getCharacteristic(charUuid)
    }

    private var dataToWrite = mutableListOf<Byte>()
    private var dataToWriteCursorBegin = 0
    private var dataToWriteCursorEnd = 0

    private fun ccidWriteCharSequenced() {
        val characteristicCAPDU = getCharacteristic(GattAttributesSpringCore.UUID_CCID_PC_TO_RDR_CHAR)

        /* Temporary workaround: we can not send to much data in one write */
        /* (we can write more than MTU but less than ~512 bytes) */
        val maxSize = 512
        if(dataToWriteCursorBegin < dataToWrite.size) {
            dataToWriteCursorEnd =  minOf(dataToWriteCursorBegin+maxSize, dataToWrite.size)
            characteristicCAPDU?.value = dataToWrite.toByteArray().sliceArray(dataToWriteCursorBegin until dataToWriteCursorEnd)
            /* If the data length is greater than MTU, Android will automatically send multiple packets */
            /* There is no need to split the data ourself  */
            mBluetoothGatt.writeCharacteristic(characteristicCAPDU)
            Log.d(TAG, "Writing ${dataToWriteCursorEnd - dataToWriteCursorBegin} bytes")
            dataToWriteCursorBegin = dataToWriteCursorEnd
        }
        else {
            Log.d(TAG, "Write finished")
            currentState = State.WaitingResponse
        }
    }

    /* Warning only use this method if you are sure to have less than 512 byte  */
    /* or if you want to use a specific characteristic */
    private fun writeChar(charUuid: UUID, data: ByteArray) {
        val characteristicCAPDU = getCharacteristic(charUuid)
        characteristicCAPDU?.value = data
        mBluetoothGatt.writeCharacteristic(characteristicCAPDU)
    }



    private fun interpretSlotsStatus(data: ByteArray) {

        if(data.isEmpty()) {
            postReaderListError(
                SCardError.ErrorCodes.PROTOCOL_ERROR,
                "Error, interpretSlotsStatus: array is empty")
            return
        }

        var slotCount = data[0]

        /* Is slot count  matching nb of bytes*/
        if (slotCount > 4 * (data.size - 1)) {
            postReaderListError(
                SCardError.ErrorCodes.PROTOCOL_ERROR,
                "Error, too much slot ($slotCount) for ${data.size - 1} bytes")
            return
        }

        /* Is slot count matching nb of readers in SCardReaderList obj */
        if(slotCount.toInt() != scardDevice.readers.size) {
            postReaderListError(
                SCardError.ErrorCodes.PROTOCOL_ERROR,
                "Error, slotCount in frame ($slotCount) does not match slotCount in SCardReaderList (${scardDevice.readers.size})")
            return
        }

        for (i in 1 until data.size) {
            for (j in 0..3) {
                var slotNumber = (i - 1) * 4 + j
                if (slotNumber < slotCount) {

                    var slotStatus = (data[i].toInt() shr j*2) and 0x03
                    Log.i(TAG, "Slot $slotNumber")

                    /* Update SCardReadList slot status */
                    scardDevice.readers[slotNumber].cardPresent =
                            !(slotStatus == SCardReader.SlotStatus.Absent.code || slotStatus == SCardReader.SlotStatus.Removed.code)

                    /* If card is not present, it can not be powered */
                    if(!scardDevice.readers[slotNumber].cardPresent) {
                        scardDevice.readers[slotNumber].cardPowered = false
                    }

                    /* If the card on the slot we used is gone */
                    if(scardDevice.ccidHandler.currentReaderIndex == slotNumber) {
                        if(!scardDevice.readers[slotNumber].cardPresent || !scardDevice.readers[slotNumber].cardPowered) {
                            currentState = State.Idle
                        }
                    }

                    when (slotStatus) {
                        SCardReader.SlotStatus.Absent.code -> Log.i(TAG, "card absent, no change since last notification")
                        SCardReader.SlotStatus.Present.code -> Log.i(TAG, "card present, no change since last notification")
                        SCardReader.SlotStatus.Removed.code  -> Log.i(TAG, "card removed notification")
                        SCardReader.SlotStatus.Inserted.code  -> Log.i(TAG, "card inserted notification")
                        else -> {
                            Log.w(TAG, "Impossible value : $slotStatus")
                        }
                    }
                    val cardChanged = (slotStatus == SCardReader.SlotStatus.Removed.code || slotStatus == SCardReader.SlotStatus.Inserted.code)
                    if(cardChanged) {
                        scardDevice.mHandler.post {
                            callbacks.onReaderStatus(
                                scardDevice.readers[slotNumber],
                                scardDevice.readers[slotNumber].cardPresent,
                                scardDevice.readers[slotNumber].cardPowered
                            )
                        }
                    }
                }
            }
        }
    }


    /**
     * Interpret slot status byte and update cardPresent and cardPowered
     *
     * @param slotStatus Byte
     * @param slot SCardReader
     */
    private fun interpretSlotsStatusInCcidHeader(slotStatus: Byte, slot: SCardReader) {

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
     * @return true if there is no error, false otherwise
     */
    private fun interpretSlotsErrorInCcidHeader(slotError: Byte, slotStatus: Byte, slot: SCardReader, postScardErrorCb: Boolean = true): Boolean {

        val commandStatus = (slotStatus.toInt() and 0b11000000) shr 6

        when (commandStatus) {
            0b00 -> {
                Log.i(TAG, "Command processed without error")
                return true
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
            SCardReader.SlotError.ICC_MUTE.code -> {
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
                return true
            }
            else -> {
                Log.w(TAG, "CCID Error code not handled")
                errorCode = SCardError.ErrorCodes.CARD_COMMUNICATION_ERROR
                detail = "CCID slot error: 0x${String.format("%02X", slotError)}"
            }
        }

        if(postScardErrorCb) {
            postCardOrReaderError(errorCode, detail, slot)
        }
        else {
            Log.e(TAG, "Error reader or card: ${errorCode.name}, $detail")
        }

        return false
    }

    /* Post error callbacks */

    internal fun postReaderListError(code : SCardError.ErrorCodes, detail: String, isFatal: Boolean = true) {
        Log.e(TAG, "Error readerList: ${code.name}, $detail")

        scardDevice.mHandler.post {
            callbacks.onReaderListError(scardDevice, SCardError(code, detail, isFatal))
        }

        /* irrecoverable error --> disconnect */
        if(isFatal) {
            process(ActionEvent.ActionDisconnect())
        }
    }

    private fun postCardOrReaderError(code : SCardError.ErrorCodes, detail: String, reader: SCardReader) {
        Log.e(TAG, "Error reader or card: ${code.name}, $detail")
       scardDevice.mHandler.post {
            callbacks.onReaderOrCardError(reader, SCardError(code, detail))
        }
    }

    /* State machine */

    internal fun process(event: ActionEvent) {

        Log.d(TAG, "Current state = ${currentState.name}")
        // Memo CRA : SCardReaderList instance = 0x${System.identityHashCode(scardDevice).toString(16).toUpperCase()}

        when (currentState) {
            State.Disconnected -> handleStateDisconnected(event)
            State.Connecting -> handleStateConnecting(event)
            State.Connected -> handleStateConnected(event)
            State.DiscoveringGatt -> handleStateDiscovering(event)
            State.ReadingInformation -> handleStateReadingInformation(event)
            State.SubscribingNotifications -> handleStateSubscribingNotifications(event)
            State.ReadingSlotsName ->  handleStateReadingSlotsName(event)
            State.ConnectingToCard -> handleStateConnectingToCard(event)
            State.Idle ->  handleStateIdle(event)
            State.ReadingPowerInfo -> handleStateReadingPowerInfo(event)
            State.WritingCommand -> handleStateWritingCommand(event)
            State.WaitingResponse -> handleStateWaitingResponse(event)
            State.Disconnecting ->  handleStateDisconnecting(event)
            else -> Log.w(TAG,"Unhandled State : $currentState")
        }
    }

    private fun handleStateDisconnected(event: ActionEvent) {
        when (event) {
            is ActionEvent.ActionConnect -> {
                Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
                currentState = State.Connecting
                connect(event.ctx)
                /* save ctx if we need to try to reconnect */
                ctx = event.ctx
            }
            else -> Log.e(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }

    private lateinit var ctx: Context
    private fun handleStateConnecting(event: ActionEvent) {
        when (event) {
            is ActionEvent.EventConnected -> {
                Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
                currentState = State.Connected
                scardDevice.mHandler.post {callbacks.onConnect(scardDevice)}
            }
            is ActionEvent.EventDisconnected -> {
                // Retry connecting
                currentState = State.Disconnected
                process(ActionEvent.ActionConnect(ctx))
                //TODO add cpt
            }
            else -> Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }

    private fun handleStateConnected(event: ActionEvent) {
        when (event) {
            is ActionEvent.ActionCreate -> {
                Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
                currentState = State.DiscoveringGatt
                create()
            }
            else ->  Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }

    private fun handleStateDiscovering(event: ActionEvent) {
        when (event) {
            is ActionEvent.EventServicesDiscovered -> {
                Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")

                if (event.status == BluetoothGatt.GATT_SUCCESS) {
                    currentState = State.ReadingInformation

                    val services =  mBluetoothGatt.services
                    Log.d(TAG, services.toString())

                    for (srv in services!!) {
                        Log.d(TAG, "Service = " + srv.uuid.toString())

                        /* Detect if CCID service is bonded or not */
                        if(srv.uuid == GattAttributesSpringCore.UUID_SPRINGCARD_CCID_PLAIN_SERVICE) {
                            GattAttributesSpringCore.isCcidServiceBonded = false
                            /* Add plain CCID status char*/
                            charUuidToRead.add(GattAttributesSpringCore.UUID_CCID_STATUS_CHAR)
                        }
                        else if(srv.uuid == GattAttributesSpringCore.UUID_SPRINGCARD_CCID_BONDED_SERVICE){
                            GattAttributesSpringCore.isCcidServiceBonded = true
                            /* Add bonded CCID status char*/
                            charUuidToRead.add(GattAttributesSpringCore.UUID_CCID_STATUS_CHAR)
                        }


                        for (chr in srv.characteristics) {
                            Log.d(TAG, "Characteristic = ${chr.uuid}")
                            val property = chr.properties

                            /* If characteristic can notify/indicate */
                            if (property and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                                Log.d(TAG, "Could be notified")
                                characteristicsCanIndicate.add(chr)
                            }

                            /* If characteristic must be read to find information */
                            if (charUuidToRead.contains(chr.uuid)) {
                                Log.d(TAG, "Could be read")
                                characteristicsToRead.add(chr)
                            }


                            if(charUuidToReadPower.contains(chr.uuid)) {
                                Log.d(TAG, "Found characteristic power")
                                characteristicsToReadPower.add(chr)
                            }
                        }
                    }

                    // trigger 1st read
                    var chr = characteristicsToRead[0]
                    mBluetoothGatt.readCharacteristic(chr)

                } else {
                    Log.w(TAG, "onServicesDiscovered received: ${event.status}")
                }
            }
            else -> Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }

    private var indexCharToBeRead: Int = 0
    private fun handleStateReadingInformation(event: ActionEvent) {
        when (event) {
            is ActionEvent.EventCharacteristicRead -> {
                Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
                if(event.status != BluetoothGatt.GATT_SUCCESS) {
                    postReaderListError(SCardError.ErrorCodes.READ_CHARACTERISTIC_FAILED, "Failed to subscribe to read characteristic ${event.characteristic}")
                    return
                }

                when(event.characteristic.uuid) {
                    GattAttributesSpringCore.UUID_MODEL_NUMBER_STRING_CHAR -> scardDevice.productName = event.characteristic.value.toString(charset("ASCII"))
                    GattAttributesSpringCore.UUID_SERIAL_NUMBER_STRING_CHAR -> {
                        scardDevice.serialNumber = event.characteristic.value.toString(charset("ASCII"))
                        scardDevice.serialNumberRaw = event.characteristic.value.toString(charset("ASCII")).hexStringToByteArray()
                    }
                    GattAttributesSpringCore.UUID_FIRMWARE_REVISION_STRING_CHAR -> scardDevice.softwareVersion = event.characteristic.value.toString(charset("ASCII"))
                    GattAttributesSpringCore.UUID_HARDWARE_REVISION_STRING_CHAR -> scardDevice.hardwareVersion = event.characteristic.value.toString(charset("ASCII"))
                    GattAttributesSpringCore.UUID_SOFTWARE_REVISION_STRING_CHAR -> {
                        var firmwareVerFull = event.characteristic.value.toString(charset("ASCII"))
                        scardDevice.firmwareVersion = firmwareVerFull
                        scardDevice.firmwareVersionMajor = firmwareVerFull.split("-")[0].split(".")[0].toInt()
                        scardDevice.firmwareVersionMinor = firmwareVerFull.split("-")[0].split(".")[1].toInt()
                        scardDevice.firmwareVersionBuild = firmwareVerFull.split("-")[1].toInt()
                    }
                    GattAttributesSpringCore.UUID_MANUFACTURER_NAME_STRING_CHAR -> scardDevice.vendorName = event.characteristic.value.toString(charset("ASCII"))
                    GattAttributesSpringCore.UUID_PNP_ID_CHAR -> scardDevice.pnpId = event.characteristic.value.byteArrayToHexString()
                    GattAttributesSpringCore.UUID_CCID_STATUS_CHAR -> {
                        val slotCount = event.characteristic.value[0]

                        if(slotCount.toInt() == 0) {
                            postReaderListError(SCardError.ErrorCodes.DUMMY_DEVICE, "This device has 0 slots")
                            return
                        }

                        /* Add n new readers */
                        for (i in 0 until slotCount) {
                            scardDevice.readers.add(SCardReader(scardDevice))
                        }

                        /* Update readers status */
                        interpretSlotsStatus(event.characteristic.value)
                    }
                    else -> {
                        Log.w(TAG, "Unhandled characteristic read : ${event.characteristic.uuid}")
                    }
                }

                indexCharToBeRead++
                if (indexCharToBeRead < characteristicsToRead.size) {
                    var chr = characteristicsToRead[indexCharToBeRead]
                    mBluetoothGatt.readCharacteristic(chr)
                }
                else {
                    Log.d(TAG, "Reading Information finished")
                    currentState = State.SubscribingNotifications
                    // Trigger 1st subscribing
                    val chr = characteristicsCanIndicate[0]
                    enableNotifications(chr)
                }
            }
            else ->  Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }

    private var indexCharToBeSubscribed: Int = 0
    private fun handleStateSubscribingNotifications(event: ActionEvent) {
        when (event) {
            is ActionEvent.EventDescriptorWrite -> {
                Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
                if(event.status != BluetoothGatt.GATT_SUCCESS) {
                    postReaderListError(SCardError.ErrorCodes.ENABLE_CHARACTERISTIC_EVENTS_FAILED, "Failed to subscribe to notification for characteristic ${event.descriptor.characteristic}")
                    return
                }

                indexCharToBeSubscribed++
                if (indexCharToBeSubscribed < characteristicsCanIndicate.size) {
                    var chr = characteristicsCanIndicate[indexCharToBeSubscribed]
                    enableNotifications(chr)
                }
                else {
                    Log.d(TAG, "Subscribing finished")

                    currentState = State.ReadingSlotsName
                    // Trigger 1st APDU to get slot name
                    writeChar(GattAttributesSpringCore.UUID_CCID_PC_TO_RDR_CHAR, scardDevice.ccidHandler.scardControl("582100".hexStringToByteArray()))
                }
            }
            else -> Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }

    private var indexSlots: Int = 0
    private var listReadersToConnect = mutableListOf<SCardReader>()
    private fun handleStateReadingSlotsName(event: ActionEvent) {
        when (event) {
            is ActionEvent.EventCharacteristicChanged -> {
                Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")

                if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_RDR_TO_PC_CHAR)
                {
                    /* Response */
                    val slotName = event.characteristic.value.slice(11 until event.characteristic.value.size).toByteArray().toString(charset("ASCII"))
                    Log.d(TAG, "Slot $indexSlots name : $slotName")
                    scardDevice.readers[indexSlots].name = slotName
                    scardDevice.readers[indexSlots].index = indexSlots

                    /* Get next slot name */
                    indexSlots++
                    if (indexSlots < scardDevice.readers.size) {
                        writeChar(GattAttributesSpringCore.UUID_CCID_PC_TO_RDR_CHAR, scardDevice.ccidHandler.scardControl("58210$indexSlots".hexStringToByteArray()))
                    }
                    else {
                        Log.d(TAG, "Reading readers name finished")

                        /* Check if there is some card already presents on the slots */
                        listReadersToConnect.clear()
                        for (slot in scardDevice.readers) {
                            if(slot.cardPresent && !slot.cardPowered) {
                                Log.d(TAG, "Slot: ${slot.name}, card present but not powered --> must connect to this card")
                                listReadersToConnect.add(slot)
                            }
                        }

                        /* If there are one card present on one or more slot --> go to state ConnectingToCard */
                        processNextSlotConnection()
                    }
                }
                else {
                    Log.w(TAG, "Received notification/indication on an unexpected characteristic  ${event.characteristic.uuid}")
                }
            }
            else -> Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }
    private var rxBuffer = mutableListOf<Byte>()
    private fun handleStateConnectingToCard(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.ActionWriting -> {
                Log.d(TAG, "Writing ${event.command.byteArrayToHexString()} on characteristic ${event.charUuid}")

                /* Clear and set data to write */
                dataToWrite.clear()
                dataToWrite.addAll(event.command.toList())

                dataToWriteCursorBegin = 0
                dataToWriteCursorEnd = 0

                /* Trigger 1st write operation */
                ccidWriteCharSequenced()
            }
            is ActionEvent.EventCharacteristicWrite -> {
                Log.d(TAG, "Write succeed")
            }
            is ActionEvent.EventCharacteristicChanged -> {

                if (event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_RDR_TO_PC_CHAR) {

                    rxBuffer.addAll(event.characteristic.value.toList())
                    /* Put data in ccid frame to get theoretical size */
                    var ccidResponse = scardDevice.ccidHandler.getCcidResponse(rxBuffer.toByteArray())
                    var slot = scardDevice.readers[ccidResponse.slotNumber.toInt()]

                    /* Update slot status (present, powered) */
                    interpretSlotsStatusInCcidHeader(
                        ccidResponse.slotStatus,
                        slot
                    )

                    /* Check slot error */
                    if (!interpretSlotsErrorInCcidHeader(
                            ccidResponse.slotError,
                            ccidResponse.slotStatus,
                            slot,
                            false // do not post callback
                        )
                    ) {
                        Log.d(TAG, "Error, do not process CCID packet, returning to Idle state")
                        /* reset rxBuffer */
                        rxBuffer = mutableListOf<Byte>()

                        /* Remove reader we just processed */
                        listReadersToConnect.remove(slot)

                        processNextSlotConnection()

                        /* Do not go further */
                        return
                    }

                    /* Check if the response is compete or not */
                    if (rxBuffer.size - CcidFrame.HEADER_SIZE != ccidResponse.length) {
                        Log.d(TAG, "Frame not complete, excepted length = ${ccidResponse.length}")
                    } else {
                        Log.d(TAG, "Frame complete, length = ${ccidResponse.length}")

                        /* reset rxBuffer */
                        rxBuffer = mutableListOf<Byte>()
                        if (ccidResponse.code == CcidResponse.ResponseCode.RDR_To_PC_DataBlock.value) {

                            // save ATR
                            slot.channel.atr = ccidResponse.payload
                            // set cardPowered flag
                            slot.cardPowered = true

                            /* Remove reader we just processed */
                            listReadersToConnect.remove(slot)

                            /* Change state if we are at the end of the list */
                            processNextSlotConnection()

                        }
                    }
                }
                else {
                    Log.w(TAG,"Received notification/indication on an unexpected characteristic  ${event.characteristic.uuid}")
                }
            }
            else -> Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }



    private fun handleStateIdle(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.ActionDisconnect -> {
                currentState = State.Disconnecting
                disconnect()
            }
            is ActionEvent.ActionWriting -> {
                currentState = State.WritingCommand
                Log.d(TAG, "Writing ${event.command.byteArrayToHexString()} on characteristic ${event.charUuid}")

                /* Clear and set data to write */
                dataToWrite.clear()
                dataToWrite.addAll(event.command.toList())

                dataToWriteCursorBegin = 0
                dataToWriteCursorEnd = 0

                /* Trigger 1st write operation */
                ccidWriteCharSequenced()
            }
            is ActionEvent.EventCharacteristicChanged -> {
                if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_STATUS_CHAR) {
                    /* Update readers status */
                    interpretSlotsStatus(event.characteristic.value)
                }
                else {
                    Log.w(TAG,"Received notification/indication on an unexpected characteristic  ${event.characteristic.uuid}")
                }
            }
            is ActionEvent.ActionReadPowerInfo -> {
                currentState = State.ReadingPowerInfo
                process(event)
            }
            else -> Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }

    private var indexCharToReadPower = 0
    private var batteryLevel = 0
    private var powerState = 0
    private fun handleStateReadingPowerInfo(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.EventCharacteristicRead -> {

                when(event.characteristic.uuid) {
                    GattAttributesSpringCore.UUID_BATTERY_LEVEL_CHAR -> batteryLevel = event.characteristic.value[0].toInt()
                    GattAttributesSpringCore.UUID_BATTERY_POWER_STATE_CHAR -> {
                        /* cf https://www.bluetooth.com/specifications/gatt/viewer?attributeXmlFile=org.bluetooth.characteristic.battery_power_state.xml*/
                        /* Check Charging (Chargeable) state */
                        val charging = 0b00110000.toByte()
                        powerState = if(event.characteristic.value[0] and charging == charging) {
                            1
                        } else {
                            2
                        }
                    }
                }

                /* Read next */
                indexCharToReadPower++
                if(indexCharToReadPower<characteristicsToReadPower.size) {
                    var chr = characteristicsToReadPower[indexCharToReadPower]
                    mBluetoothGatt.readCharacteristic(chr)
                }
                else {
                    currentState = State.Idle
                    /* read done --> send callback */
                    scardDevice.mHandler.post {
                        callbacks.onPowerInfo(
                            scardDevice,
                            powerState,
                            batteryLevel
                        )
                    }
                }
            }
            is ActionEvent.ActionReadPowerInfo -> {
                /* Trigger 1st read */
                indexCharToReadPower = 0
                var chr = characteristicsToReadPower[indexCharToReadPower]
                mBluetoothGatt.readCharacteristic(chr)
            }
            is ActionEvent.EventCharacteristicChanged -> {
                if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_STATUS_CHAR) {
                    /* Update readers status */
                    interpretSlotsStatus(event.characteristic.value)
                }
                else {
                    Log.w(TAG,"Received notification/indication on an unexpected characteristic  ${event.characteristic.uuid}")
                }
            }
            else -> Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }

    private fun handleStateWritingCommand(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.EventCharacteristicWrite -> {

                if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_PC_TO_RDR_CHAR) {
                    if(event.status == BluetoothGatt.GATT_SUCCESS) {
                        ccidWriteCharSequenced()
                    }
                    else {
                        currentState = State.Idle
                        postReaderListError(SCardError.ErrorCodes.WRITE_CHARACTERISTIC_FAILED,"Writing on characteristic ${event.characteristic.uuid} failed with status ${event.status} (BluetoothGatt constant)")
                    }
                }
                else {
                    Log.w(TAG,"Received written indication on an unexpected characteristic  ${event.characteristic.uuid}")
                }
            }
            is ActionEvent.EventCharacteristicChanged -> {
                if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_STATUS_CHAR) {
                    /* Update readers status */
                    interpretSlotsStatus(event.characteristic.value)
                }
                else {
                    Log.w(TAG,"Received notification/indication on an unexpected characteristic  ${event.characteristic.uuid}")
                }
            }
            else -> Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }


    private fun handleStateWaitingResponse(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.EventCharacteristicChanged -> {

                if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_RDR_TO_PC_CHAR) {

                    rxBuffer.addAll(event.characteristic.value.toList())
                    /* Put data in ccid frame to get theoretical size */
                    var ccidResponse = scardDevice.ccidHandler.getCcidResponse(rxBuffer.toByteArray())

                    var slot = scardDevice.readers[ccidResponse.slotNumber.toInt()]

                    /* Update slot status (present, powered) */
                    interpretSlotsStatusInCcidHeader(ccidResponse.slotStatus, slot)

                    /* Check slot error */
                    if(!interpretSlotsErrorInCcidHeader(ccidResponse.slotError, ccidResponse.slotStatus, slot)) {
                        Log.d(TAG, "Error, do not process CCID packet, returning to Idle state")
                        currentState = State.Idle
                        /* reset rxBuffer */
                        rxBuffer = mutableListOf<Byte>()
                        /* Do not go further */
                        return
                    }

                    /* Check if the response is compete or not */
                    if( rxBuffer.size-CcidFrame.HEADER_SIZE != ccidResponse.length ) {
                        Log.d(TAG, "Frame not complete, excepted length = ${ccidResponse.length}")
                    }
                    else {
                        currentState = State.Idle
                        Log.d(TAG, "Frame complete, length = ${ccidResponse.length}")

                        /* reset rxBuffer */
                        rxBuffer = mutableListOf<Byte>()

                        when {
                            ccidResponse.code == CcidResponse.ResponseCode.RDR_To_PC_Escape.value -> when (scardDevice.ccidHandler.commandSend) {
                                CcidCommand.CommandCode.PC_To_RDR_Escape -> scardDevice.mHandler.post {
                                    callbacks.onControlResponse(
                                        scardDevice,
                                        event.characteristic.value
                                    )
                                }
                                else -> postReaderListError(SCardError.ErrorCodes.DIALOG_ERROR, "Unexpected CCID response (${ccidResponse.code}) for command : ${scardDevice.ccidHandler.commandSend}")
                            }
                            ccidResponse.code == CcidResponse.ResponseCode.RDR_To_PC_DataBlock.value -> when (scardDevice.ccidHandler.commandSend) {
                                CcidCommand.CommandCode.PC_To_RDR_XfrBlock -> {
                                    if (ccidResponse.slotNumber > scardDevice.readers.size) {
                                        postReaderListError(SCardError.ErrorCodes.PROTOCOL_ERROR,
                                            "Error, slot number specified (${ccidResponse.slotNumber}) greater than maximum slot (${scardDevice.readers.size - 1}), maybe the packet is incorrect"
                                        )
                                    } else {

                                        currentState = State.Idle
                                        scardDevice.mHandler.post {
                                            callbacks.onTransmitResponse(
                                                slot.channel,
                                                ccidResponse.payload
                                            )
                                        }
                                    }
                                }
                                CcidCommand.CommandCode.PC_To_RDR_IccPowerOn -> {
                                    // save ATR
                                    slot.channel.atr = ccidResponse.payload
                                    // set cardPowered flag
                                    slot.cardPowered = true
                                    // change state
                                    currentState = State.Idle
                                    // call callback
                                    scardDevice.mHandler.post { callbacks.onCardConnected(slot.channel) }
                                }
                                else -> postReaderListError(SCardError.ErrorCodes.DIALOG_ERROR, "Unexpected CCID response (${ccidResponse.code}) for command : ${scardDevice.ccidHandler.commandSend}")
                            }
                            ccidResponse.code == CcidResponse.ResponseCode.RDR_To_PC_SlotStatus.value -> when (scardDevice.ccidHandler.commandSend) {
                                CcidCommand.CommandCode.PC_To_RDR_GetSlotStatus -> {
                                    // do nothing
                                    Log.d(TAG, "Reader Status --> Cool! ...but useless")
                                }
                                CcidCommand.CommandCode.PC_To_RDR_IccPowerOff -> {
                                    slot.cardPowered = false
                                    scardDevice.mHandler.post {callbacks.onCardDisconnected(slot.channel)}
                                }
                                CcidCommand.CommandCode.PC_To_RDR_XfrBlock -> {
                                    // TODO CRA scardDevice.mHandler.post {callbacks?.onReaderStatus()}
                                  //  scardDevice.mHandler.post {callbacks?.onCardConnected(channel)}
                                }
                                CcidCommand.CommandCode.PC_To_RDR_IccPowerOn -> {
                                    var channel = slot.channel
                                    slot.cardPowered = true
                                    scardDevice.mHandler.post {callbacks.onCardConnected(channel)}
                                    // TODO onReaderOrCardError
                                }
                                else -> postReaderListError(SCardError.ErrorCodes.DIALOG_ERROR, "Unexpected CCID response (${ccidResponse.code}) for command : ${scardDevice.ccidHandler.commandSend}")
                            }
                            else -> postReaderListError(SCardError.ErrorCodes.DIALOG_ERROR, "Unknown CCID response (${ccidResponse.code}) for command : ${scardDevice.ccidHandler.commandSend}")
                        }
                    }
                }
                else if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_STATUS_CHAR) {
                    /* Update readers status */
                    interpretSlotsStatus(event.characteristic.value)
                }
                else {
                    Log.w(TAG,"Received notification/indication on an unexpected characteristic  ${event.characteristic.uuid}")
                }
            }
            else -> Log.w(TAG ,"Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }

    private fun handleStateDisconnecting(event: ActionEvent) {
        when (event) {
            is ActionEvent.EventDisconnected -> {
                Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
                currentState = State.Disconnected
                scardDevice.mHandler.post {callbacks.onReaderListClosed(scardDevice)}

                // Reset all lists
                characteristicsCanIndicate.clear()
                indexCharToBeSubscribed = 0

                characteristicsToRead.clear()
                indexCharToBeRead = 0

                scardDevice.readers.clear()
                indexSlots = 0

                mBluetoothGatt.close()
            }
            else -> Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }


    private fun enableNotifications(chr : BluetoothGattCharacteristic) {
        mBluetoothGatt.setCharacteristicNotification(chr, true)
        val descriptor = chr.descriptors[0]
        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (!mBluetoothGatt.writeDescriptor(descriptor)) {
            postReaderListError(SCardError.ErrorCodes.ENABLE_CHARACTERISTIC_EVENTS_FAILED,"Failed to write in descriptor, to enable notification on characteristic ${chr.uuid}")
            return
        }
    }

    private fun processNextSlotConnection() {
        /* If there are one card present on one or more slot --> go to state ConnectingToCard */
        if(listReadersToConnect.size > 0) {
            currentState = State.ConnectingToCard
             listReadersToConnect[0].cardConnect()
        }
        /* Otherwise go to idle state */
        else {
            currentState = State.Idle
            scardDevice.mHandler.post { callbacks.onReaderListCreated(scardDevice) }
        }
    }
}