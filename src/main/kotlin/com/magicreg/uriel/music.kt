package com.magicreg.uriel

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URL
import javax.sound.midi.*

private const val MIDI_FILE_TYPE = 1
private const val RUNNING_CHECK_FREQUENCY = 100L

fun saveMusic(output: OutputStream, data: Any?): Boolean {
    if (data is Song)
        return saveMusic(output, data.generateMidi())
    if (data is Sequence)
        return MidiSystem.write(data, MIDI_FILE_TYPE, output) > 0
    return false
}

enum class SongStatus {
    STOPPED, PLAYING, RECORDING
}

class Song(vararg args: Any) {

    var name: String? = null
    var artist: String? = null
    private var sequence: Sequence? = null
    private var sequencer: Sequencer? = null
    private var soundbank: Soundbank? = null
    init {
        for (arg in args)
            setValue(arg)
    }

    var status: SongStatus = SongStatus.STOPPED
        get() {
            if (sequencer == null)
                return SongStatus.STOPPED
            if (sequencer!!.isRunning)
                return SongStatus.PLAYING
            if (sequencer!!.isRecording)
                return SongStatus.RECORDING
            return SongStatus.STOPPED
        }

    fun play() {
        if (sequencer == null)
            initSequencer()
        if (sequence != null) {
            sequencer!!.sequence = sequence
            sequencer!!.open();
            sequencer!!.start()
            while (sequencer!!.isRunning)
                Thread.sleep(RUNNING_CHECK_FREQUENCY);
            sequencer!!.close()
        }
    }

    fun stop() {
        if (sequencer != null)
            sequencer!!.stop()
    }

    fun getPosition(): Double {
        if (sequencer != null)
            return sequencer!!.tickPosition.toDouble() / sequencer!!.tickLength
         return 0.0
    }

    fun setPosition(pos: Double): Boolean {
        if (pos < 0 || pos > 1)
            return false
        if (pos > 0 && sequence != null && sequencer == null) {
            initSequencer()
            sequencer!!.sequence = sequence
        }
        if (sequencer != null) {
            sequencer!!.tickPosition = (pos * sequencer!!.tickLength).toLong()
            return true
        }
        return false
    }

    fun generateMidi(): Sequence {
        return sequence ?: Sequence(1f, 1)
    }

    override fun toString(): String {
        val a = if (artist == null) "?" else " by $artist"
        return "(Song "+(name?:"")+a+")"
    }

    private fun setValue(value: Any?) {
        when (value) {
            is Sequence -> sequence = value
            is Sequencer -> sequencer = value
            is Soundbank -> soundbank = value
            is File -> sequence = getSequence(FileInputStream(value), true, value.toString())
            is URI -> sequence = getSequence(value.toURL().openStream(), true, value.toString())
            is URL -> sequence = getSequence(value.openStream(), true, value.toString())
            is InputStream -> sequence = getSequence(value, false)
            is CharSequence -> if (name == null) name = value.toString() else if (artist == null) artist = value.toString()
            is Array<*> -> setValue(listOf(*value))
            is Map<*,*> -> setValue(value.values)
            is Collection<*> -> for (item in value) setValue(item)
            is Song -> copySong(value)
        }
    }

    private fun getSequence(input: InputStream, closeStream: Boolean, id: String? = null): Sequence {
        val seq = MidiSystem.getSequence(input)
        if (closeStream)
            input.close()
        if (id != null && name == null) {
            val parts = id.trim().split("#")[0].split("?")[0].split("/")
            for (p in parts.size-1 downTo 0) {
                val part = parts[p].trim()
                if (part.isNotEmpty()) {
                    name = part.split(".")[0].trim()
                    break
                }
            }
        }
        return seq
    }

    private fun initSequencer() {
        if (soundbank == null) {
            val prop = System.getProperty("uriel.midi.soundbank")
            if (prop != null)
                soundbank = MidiSystem.getSoundbank(File(prop))
        }
        val synth = MidiSystem.getSynthesizer()
        synth.open()
        if (soundbank != null) {
            synth.unloadAllInstruments(synth.defaultSoundbank)
            synth.loadAllInstruments(soundbank)
        }
        sequencer = MidiSystem.getSequencer(false)
        sequencer!!.transmitter.receiver = synth.receiver
    }

    private fun copySong(song: Song) {
        name = song.name
        artist = song.artist
        sequence = song.sequence
        sequencer = song.sequencer
        soundbank = song.soundbank
    }
}
