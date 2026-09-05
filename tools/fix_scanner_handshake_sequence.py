from pathlib import Path

p = Path('app/src/main/java/com/pylikv/tachowatch/FullDtcoScannerActivity.kt')
text = p.read_text(encoding='utf-8')
old = '''        log("GATT readable=${readQueue.size}, subscribable=${subscribeQueue.size}")
        phase = Phase.SUBSCRIBE
        subscribeNext(g)
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNext(g: BluetoothGatt) {
        if (!running || phase != Phase.SUBSCRIBE) return
        val item = if (subscribeQueue.isEmpty()) null else subscribeQueue.removeFirst()
        if (item == null) {
            phase = Phase.GATT_READ
            readNextGatt(g)
            return
        }
        val c = item.characteristic
        val d = c.getDescriptor(CCCD)
        if (d == null || !g.setCharacteristicNotification(c, true)) {
            handler.postDelayed({ subscribeNext(g) }, STEP_DELAY_MS)
            return
        }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, item.value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") d.value = item.value
            @Suppress("DEPRECATION") g.writeDescriptor(d)
        }
        if (!ok) handler.postDelayed({ subscribeNext(g) }, STEP_DELAY_MS)
    }
'''
new = '''        log("GATT readable=${readQueue.size}, subscribable=${subscribeQueue.size}")
        // DTCO Remote HMI is order-sensitive.  Do NOT subscribe every CCCD here:
        // first Diagnostics FIFO, then Diagnostics Credits, exactly like the stable
        // LiveDidDiagnostic transport. Download/other notifications stay untouched.
        subscribeQueue.clear()
        val diag = g.getService(DIAG_SERVICE)
        val fifo = diag?.getCharacteristic(DIAG_FIFO)
        if (fifo == null) {
            log("DIAGNOSTICS FIFO NOT FOUND")
            finish()
            return
        }
        phase = Phase.SUBSCRIBE
        subscribeDiagnostic(g, fifo)
    }

    @SuppressLint("MissingPermission")
    private fun subscribeDiagnostic(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (!running || phase != Phase.SUBSCRIBE) return
        val d = c.getDescriptor(CCCD)
        if (d == null || !g.setCharacteristicNotification(c, true)) {
            log("DIAG SUBSCRIBE START FAILED ${c.uuid}")
            finish()
            return
        }
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") d.value = value
            @Suppress("DEPRECATION") g.writeDescriptor(d)
        }
        if (!ok) {
            log("DIAG CCCD WRITE START FAILED ${c.uuid}")
            finish()
        }
    }
'''
if old not in text:
    raise SystemExit('subscription block not found')
text = text.replace(old, new)
old2 = '''        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("CCCD ${descriptor.characteristic.uuid} status=$status")
            if (phase == Phase.SUBSCRIBE) handler.postDelayed({ subscribeNext(g) }, STEP_DELAY_MS)
        }
'''
new2 = '''        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.characteristic.uuid
            log("CCCD $uuid status=$status")
            if (phase != Phase.SUBSCRIBE) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("DIAG CCCD ERROR status=$status")
                finish()
                return
            }
            if (uuid == DIAG_FIFO) {
                val credits = g.getService(DIAG_SERVICE)?.getCharacteristic(DIAG_CREDITS)
                if (credits == null) {
                    log("DIAGNOSTICS CREDITS NOT FOUND")
                    finish()
                } else handler.postDelayed({ subscribeDiagnostic(g, credits) }, 120L)
            } else if (uuid == DIAG_CREDITS) {
                // No generic GATT reads exist on the tested DTCO, and touching other
                // subscriptions before RHMI caused remote disconnect status=19.
                handler.postDelayed({ beginHandshake(g) }, 180L)
            }
        }
'''
if old2 not in text:
    raise SystemExit('descriptor block not found')
text = text.replace(old2, new2)
old3 = '''        sendCredit(g, DIAG_SERVICE, DIAG_CREDITS)
        if (g.getService(DOWNLOAD_SERVICE) != null) {
            sendCredit(g, DOWNLOAD_SERVICE, DOWNLOAD_CREDITS)
            log("Download service detected; only flow-control/notifications, no download command")
        }
'''
new3 = '''        log("Diagnostics-only handshake; Download FIFO intentionally untouched")
        sendCredit(g, DIAG_SERVICE, DIAG_CREDITS)
'''
if old3 not in text:
    raise SystemExit('handshake block not found')
text = text.replace(old3, new3)
p.write_text(text, encoding='utf-8')
print('Applied ordered Diagnostics FIFO -> Credits -> RHMI handshake')
