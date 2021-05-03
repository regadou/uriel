package com.magicreg.uriel

import java.io.OutputStream
import javax.sound.midi.*
import kotlin.math.roundToInt

private val SONG_PROPERTIES = listOf("name", "artist", "sequence", "sequencer", "bpm", "loops", "position", "beats", "seconds", "tracks")
private var SOUNDBANK: Soundbank? = null
private val MIDI_NOTES = arrayOf('C','#','D','#','E','F','#','G','#','A','#','B')
private const val BASE_MIDI_NOTE = 12
private const val MIDI_FILE_TYPE = 1
private const val RUNNING_CHECK_FREQUENCY = 500L
private const val DEFAULT_DIVISION_TYPE = Sequence.PPQ
private const val DEFAULT_TICK_RESOLUTION = 240
private val DIVISION_TYPES = arrayOf<Float>(
    Sequence.PPQ,
    Sequence.SMPTE_24,
    Sequence.SMPTE_25,
    Sequence.SMPTE_30,
    Sequence.SMPTE_30DROP
)

fun initMusicFunctions() {
    addType(Song::class, "song")
    addFunction(Type("track", null, Track::class) { params ->
        val executedParams = params.map { execute(it) }.toTypedArray()
        UTrack(*executedParams).generateMidi()
    })
    addFunction(Type("loop", 3, Loop::class) { params ->
        var count: Int? = null
        var start: Float? = null
        var end: Float? = null
        for (param in params) {
            val value = toNumber(execute(param))
            if (count == null)
                count = value.toInt()
            else if (start == null)
                start = value.toFloat()
            else if (end == null)
                end = value.toFloat()
        }
        Loop(count ?: 1, start, end)
    })
    addFunction(Action("soundbank", 1) { params ->
        val param = if (params.isEmpty()) null else execute(params[0])
        if (param == null)
            SOUNDBANK = null
        else if (param is Soundbank)
            SOUNDBANK = param
        SOUNDBANK
    })
}

fun saveMusic(output: OutputStream, data: Any?): Boolean {
    if (data is Song)
        return saveMusic(output, data.generateMidi())
    if (data is Sequence)
        return MidiSystem.write(data, MIDI_FILE_TYPE, output) > 0
    return false
}

fun getMusicalNote(name: String): Note? {
    return try { Note(name) } catch (e: Throwable) { null }
}

enum class SongStatus {
    STOPPED, PLAYING, RECORDING
}

enum class BeatMeasure(val offset: Int) {
    POSITION(1), DURATION(0)
}

class Note(txt: String) {
    val name = validateNoteName(txt)
    val midi = computeMidiNote(name)

    override fun toString(): String {
        return name
    }
}

class Loop(val count: Int, val start: Float?, val end: Float?) {
    override fun toString(): String {
        if (start == null && end == null)
            return "(Loop $count)"
        if (start == null)
            return "(Loop $count to $end)"
        if (end == null)
            return "(Loop $count from $start)"
        return "(Loop $count from $start to $end)"
    }
}

class Song(vararg args: Any?) {

    var name: String? = null
    var artist: String? = null
    private var cachedbpm: Float? = null
    private var loops: Loop? = null
    private var sequence: Sequence? = null
    private var sequencer: Sequencer? = null
    init {
        for (arg in args)
            setValue(execute(arg))
    }

    val properties: Map<String,Any?>
        get() {
            val map = mutableMapOf<String,Any?>()
            for (p in SONG_PROPERTIES)
                map[p] = property(p)
            return map
        }

    val status: SongStatus
        get() {
            if (sequencer == null)
                return SongStatus.STOPPED
            if (sequencer!!.isRecording)
                return SongStatus.RECORDING
            if (sequencer!!.isRunning)
                return SongStatus.PLAYING
            return SongStatus.STOPPED
        }

    fun play(block: Boolean): Boolean {
        val song = sequence ?: sequencer?.sequence ?: return false
        val seq = sequencer ?: initSequencer()
        if (cachedbpm != null) {
            seq.tempoInBPM = cachedbpm!!
            seq.tempoFactor = 1.0f
            cachedbpm = null
        }
        seq.sequence = song
        if (loops != null) {
            seq.loopCount = loops!!.count
            seq.loopStartPoint = beatsToTicks(loops!!.start ?: 1f, BeatMeasure.POSITION, song.resolution)
            seq.loopEndPoint = if (loops?.end == null) song.tickLength else beatsToTicks(loops!!.end!!, BeatMeasure.POSITION, song.resolution)
        }
        else {
            seq.loopCount = 0
            seq.loopStartPoint = 0
            seq.loopEndPoint = song.tickLength
        }
        seq.open();
        seq.start()
        if (block) {
            while (seq.isRunning)
                Thread.sleep(RUNNING_CHECK_FREQUENCY);
            seq.close()
        }
        return true
    }

    fun stop(): Boolean {
        if (sequencer != null && sequencer!!.isRunning) {
            sequencer!!.stop()
            sequencer!!.close()
            return true
        }
        return false
    }

    fun property(name: String): Any? {
        if (SONG_PROPERTIES.indexOf(name) < 0)
            return null
        return when(name) {
            "name" -> name
            "artist" -> artist
            "sequence" -> sequence ?: (property("sequencer") as Sequencer).sequence
            "sequencer" -> sequencer ?: initSequencer()
            "bpm" -> (cachedbpm ?: sequencer?.tempoInBPM ?: 0)
            "loops" -> loops
            "position" -> if (sequencer == null) 0 else (sequencer!!.tickPosition.toDouble() / sequencer!!.tickLength)
            "beats" -> {
                val seq = property("sequence") as Sequence?
                if (seq == null) 0 else ticksToBeats(seq.tickLength, BeatMeasure.DURATION, seq.resolution)
            }
            "seconds" -> {
                val seq = property("sequence") as Sequence?
                if (seq == null) 0 else seq.microsecondLength / 1e6
            }
            "tracks" -> (sequence ?: sequencer?.sequence)?.tracks?.size ?: 0
            else -> throw RuntimeException("Configuration problem with song property: $name")
        }
    }

    fun position(pos: Double): Boolean {
        //TODO: must convert the value to beats
        if (pos < 0 || pos > 1) //TODO: >= 1 means we are using tick or beat units
            return false
        if (pos > 0 && sequence != null && sequencer == null)
            initSequencer()
        if (sequencer != null) {
            sequencer!!.tickPosition = (pos * sequencer!!.tickLength).toLong()
            return true
        }
        return false
    }

    fun bpm(bpm: Float): Boolean {
        if (bpm < 1)
            return false
        cachedbpm = bpm
        return true
    }

    fun loops(loops: Loop) {
        this.loops = loops
    }

    fun tracks(track: Int): List<String> {
        val events = mutableListOf<String>()
        val tracks = generateMidi().tracks
        if (track >= 0 && track < tracks.size) {
            val t = tracks[track]
            val resolution = generateMidi().resolution
            for (e in 0 until t.size())
                events.add(printMidiEvent(t.get(e), resolution))
        }
        return events
    }

    fun addTrack(track: Track) {
        val newTrack = generateMidi().createTrack()
        for (e in 0 until track.size())
            newTrack.add(track.get(e))
        //TODO: check if division types and tick resolutions are not the same, we need adjustments
    }

    fun generateMidi(): Sequence {
        if (sequence == null)
            sequence = sequencer?.sequence ?: Sequence(DEFAULT_DIVISION_TYPE, DEFAULT_TICK_RESOLUTION)
        return sequence!!
    }

    override fun toString(): String {
        val a = if (artist == null) "" else " by $artist"
        return "(Song "+(name?:"?")+a+")"
    }

    private fun setValue(value: Any?) {
        when (value) {
            is Sequence -> sequence = value
            is Sequencer -> sequencer = value
            is Song -> copySong(value)
            is Track -> addTrack(value)
            is Number -> cachedbpm = value.toFloat()
            is Loop -> loops = value
            is CharSequence -> if (name == null) name = value.toString() else if (artist == null) artist = value.toString()
            is Array<*> -> for (item in value) setValue(item)
            is Collection<*> -> for (item in value) setValue(item)
            is Map<*,*> -> setValues(value as Map<Any?,Any?>)
        }
    }

    private fun setValues(values: Map<Any?,Any?>) {
        for (key in values.keys) {
            val value = values[key]
            when (key) {
                "name" -> name = value?.toString()
                "artist" -> artist = value?.toString()
                "bpm" -> cachedbpm = toFloat(value)
                "loops" -> {
                    if (value is Loop)
                        loops = value
                    else if (value is Array<*>)
                        loops = getFunction("loop")?.execute(*value) as Loop
                    else if (value is Collection<*>)
                        loops = getFunction("loop")?.execute(*value.toTypedArray()) as Loop
                    else if (value != null)
                        loops = getFunction("loop")?.execute(arrayOf(value)) as Loop
                    else
                        loops = null
                }
                "position" -> position(toDouble(value))
                "sequence" -> {
                    if (value is Sequence)
                        sequence = value
                    else if (value == null)
                        sequence = null
                }
                "sequencer" -> {
                    if (value is Sequencer)
                        sequencer = value
                    else if (value == null)
                        sequencer = null
                }
            }
        }
    }

    private fun initSequencer(): Sequencer {
        val synth = MidiSystem.getSynthesizer()
        synth.open()
        if (getSoundbank() != synth.defaultSoundbank) {
            synth.unloadAllInstruments(synth.defaultSoundbank)
            synth.loadAllInstruments(SOUNDBANK)
        }
        sequencer = MidiSystem.getSequencer(false)
        sequencer!!.transmitter.receiver = synth.receiver
        return sequencer!!
    }

    private fun copySong(song: Song) {
        name = song.name
        artist = song.artist
        sequence = song.sequence
        sequencer = song.sequencer
    }
}

private class Sound(
   val note: Note,
   val position: Float,
   val duration: Float,
   val velocity: Float = 1.0f
) {
    override fun toString(): String {
        return "($note $position $duration $velocity)"
    }
}

private class UTrack(vararg args: Any?) {

    private var channel: Int? = null
    private var instrument: String? = null
    private val sounds = mutableListOf<Sound>()

    init {
        for (arg in args)
            setValue(execute(arg))
    }

    fun generateMidi(): Track {
        val divType = DEFAULT_DIVISION_TYPE
        val resolution = DEFAULT_TICK_RESOLUTION
        val seq = Sequence(divType, resolution, 1)
        val track = seq.tracks[0]
        val ch = if (channel == null) 0 else channel!!-1
        if (instrument != null) {
            for (i in getSoundbank().instruments) {
                if (i.name == instrument) {
                    val msg = ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, i.patch.program, i.patch.bank)
                    track.add(MidiEvent(msg, 0))
                    break
                }
            }
        }
        for (sound in sounds) {
            val offset = beatsToTicks(sound.position, BeatMeasure.POSITION, resolution)
            val duration = (sound.duration * resolution).toInt()
            val velocity = (sound.velocity * 127).toInt()
            val note = sound.note.midi
            track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, ch, note, velocity), offset))
            track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, ch, note, velocity), offset+duration))
        }
        return track
    }

    fun addSound(values: Collection<Any?>): Boolean {
        var note: Note? = null
        var position: Float? = null
        var duration: Float? = null
        var velocity: Float? = null
        for (v in values) {
            val value = execute(v)
            if (value is Note)
                note = value
            else if (value is Number) {
                if (position == null)
                    position = value.toFloat()
                else if (duration == null)
                    duration = value.toFloat()
                else if (velocity == null)
                    velocity = value.toFloat()
            }
            else {
                val n = getMusicalNote(toString(value))
                if (n != null)
                    note = n
            }
        }
        if (note == null || position == null)
            return false
        if (duration == null) {
            duration = position
            if (sounds.isEmpty())
                position = 0.0f
            else {
                val sound = sounds[sounds.size-1]
                position = sound.position + sound.duration
            }
        }
        return sounds.add(Sound(note, position, duration, velocity ?: 1.0f))
    }

    override fun toString(): String {
        return "(Track "+(instrument?:"?")+" on channel "+channel+" with "+sounds.size+" sounds)"
    }

    private fun setValue(value: Any?) {
        when (value) {
            is Number -> {
                if (channel == null)
                    setProperties(mapOf<Any?,Any?>("channel" to value))
                else if (instrument == null)
                    setProperties(mapOf<Any?,Any?>("instrument" to value))
            }
            is CharSequence -> setProperties(mapOf<Any?,Any?>("instrument" to value))
            is Map<*,*> -> setProperties(value as Map<Any?,Any?>)
            is Array<*>, is Collection<*> -> addSound(toCollection(execute(value)))
        }
    }

    private fun setProperties(map: Map<Any?,Any?>) {
        for (key in map.keys) {
            val value = execute(map[key])
            when (key.toString()) {
                "channel" -> {
                    val n = toInt(value)
                    if (channel == null && n > 0 && n <= 16)
                        channel = n
                }
                "instrument" -> {
                    val param: Any = if (value is Number) value.toInt() else value.toString()
                    val inst = getInstrumentName(param, channel == 10)
                    if (inst != null)
                        instrument = inst
                }
                "sound" -> addSound(toCollection(value))
                "sounds" -> {
                    for (sound in toCollection(value))
                        addSound(toCollection(execute(sound)))
                }
            }
        }
    }
}

private fun getSoundbank(): Soundbank {
    if (SOUNDBANK == null) {
        for (value in (Resource(null).getData() as Map<Any?, Any?>).values) {
            if (value is Soundbank) {
                SOUNDBANK = value
                break
            }
        }
        if (SOUNDBANK == null)
            SOUNDBANK = MidiSystem.getSynthesizer().defaultSoundbank
    }
    return SOUNDBANK!!
}

private fun validateNoteName(txt: String): String {
    if (txt.length == 2 || txt.length == 3) {
        var letter = txt.substring(0, 1).toUpperCase()[0]
        var mod = ""
        var level = txt[1]
        if (txt.length == 3) {
            if (txt[1] == 'b' || txt[1] == '#') {
                mod = txt[1].toString()
                level = txt[2]
            }
            else if (txt[2] == 'b' || txt[2] == '#')
                mod = txt[2].toString()
        }
        if (letter >= 'A' && letter <= 'G' && level >= '0' && level <= '9')
            return "$letter$mod$level"
    }
    throw IllegalArgumentException("Invalid musical note: "+txt)
}

private fun computeMidiNote(name: String): Int {
    val note = MIDI_NOTES.indexOf(name[0]) + (12 * name.substring(name.length-1).toInt())
    if (name.length == 2)
        return note
    if (name[1] == '#')
        return note+1
    return note-1
}

private fun noteFromMidi(midi: Int): Note {
    val level = midi / 12
    val note = midi % 12
    var letter = MIDI_NOTES[note].toString()
    if (letter == "#")
        letter = MIDI_NOTES[note-1] + letter
    return Note(letter+level)
}

private fun getInstrumentName(value: Any, percussion: Boolean): String? {
    val name: String? = if (value is CharSequence) value.toString().toLowerCase() else null
    val program: Int? = if (value is Number) value.toInt() else null
    if (name == null && program == null)
        return null
    var drumLevel = 0
    for (i in getSoundbank().getInstruments()) {
        val patch = i.patch
        if (patch.bank > 0)
            drumLevel = 1
        else if (patch.bank == 0 && drumLevel == 1)
            drumLevel = 2
        if (percussion) {
            if (drumLevel < 2)
                continue
        }
        else if (drumLevel > 1)
            break
        if (i.name.toLowerCase() == name)
            return i.name
        if (patch.program == program)
            return i.name
    }
    return null
}

private fun printMidiEvent(event: MidiEvent, resolution: Int): String {
    val beat = ticksToBeats(event.tick, BeatMeasure.POSITION, resolution)
    val message = event.message
    if (message is ShortMessage) {
        val channel = message.channel + 1
        val txt = when (message.command) {
            ShortMessage.NOTE_ON -> ""+noteFromMidi(message.data1)+" "+(message.data2 / 127.0 * 100).roundToInt()+"%"
            ShortMessage.NOTE_OFF -> ""+noteFromMidi(message.data1)+" 0%"
            ShortMessage.PROGRAM_CHANGE -> getInstrumentName(message.data1, channel==10)
            else -> "command="+getCommandName(message.command)
        }
        return "($beat $txt channel=$channel)"
    }
    return "($beat $message)"
}

private fun ticksToBeats(ticks: Long, measure: BeatMeasure, resolution: Int = DEFAULT_TICK_RESOLUTION): Float {
    return ticks.toFloat() / resolution + measure.offset
}

private fun beatsToTicks(beats: Float, measure: BeatMeasure, resolution: Int = DEFAULT_TICK_RESOLUTION): Long {
    return ((beats - measure.offset) * resolution).toLong()
}

private fun getCommandName(command: Int): String {
    return when (command) {
        ShortMessage.ACTIVE_SENSING -> "ACTIVE_SENSING"
        ShortMessage.CHANNEL_PRESSURE -> "CHANNEL_PRESSURE"
        ShortMessage.CONTINUE -> "CONTINUE"
        ShortMessage.CONTROL_CHANGE -> "CONTROL_CHANGE"
        ShortMessage.END_OF_EXCLUSIVE -> "END_OF_EXCLUSIVE"
        ShortMessage.MIDI_TIME_CODE -> "MIDI_TIME_CODE"
        ShortMessage.NOTE_OFF -> "NOTE_OFF"
        ShortMessage.NOTE_ON -> "NOTE_ON"
        ShortMessage.PITCH_BEND -> "PITCH_BEND"
        ShortMessage.POLY_PRESSURE -> "POLY_PRESSURE"
        ShortMessage.PROGRAM_CHANGE -> "PROGRAM_CHANGE"
        ShortMessage.SONG_POSITION_POINTER -> "SONG_POSITION_POINTER"
        ShortMessage.SONG_SELECT -> "SONG_SELECT"
        ShortMessage.START -> "START"
        ShortMessage.STOP -> "STOP"
        ShortMessage.SYSTEM_RESET -> "SYSTEM_RESET"
        ShortMessage.TIMING_CLOCK -> "TIMING_CLOCK"
        ShortMessage.TUNE_REQUEST -> "TUNE_REQUEST"
        else -> command.toString()
    }
}