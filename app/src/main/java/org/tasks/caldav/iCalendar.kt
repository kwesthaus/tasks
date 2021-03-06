package org.tasks.caldav

import at.bitfire.ical4android.DateUtils.ical4jTimeZone
import at.bitfire.ical4android.Task
import at.bitfire.ical4android.Task.Companion.tasksFromReader
import com.todoroo.andlib.utility.DateUtilities
import com.todoroo.astrid.dao.TaskDao
import com.todoroo.astrid.data.Task.Companion.HIDE_UNTIL_SPECIFIC_DAY
import com.todoroo.astrid.data.Task.Companion.HIDE_UNTIL_SPECIFIC_DAY_TIME
import com.todoroo.astrid.data.Task.Companion.URGENCY_SPECIFIC_DAY
import com.todoroo.astrid.data.Task.Companion.URGENCY_SPECIFIC_DAY_TIME
import com.todoroo.astrid.helper.UUIDHelper
import com.todoroo.astrid.service.TaskCreator
import net.fortuna.ical4j.model.Date
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.parameter.RelType
import net.fortuna.ical4j.model.property.*
import org.tasks.Strings.isNullOrEmpty
import org.tasks.caldav.GeoUtils.equalish
import org.tasks.caldav.GeoUtils.toGeo
import org.tasks.caldav.GeoUtils.toLikeString
import org.tasks.data.*
import org.tasks.date.DateTimeUtils.newDateTime
import org.tasks.date.DateTimeUtils.toDateTime
import org.tasks.jobs.WorkManager
import org.tasks.location.GeofenceApi
import org.tasks.preferences.Preferences
import org.tasks.repeats.RecurrenceUtils.newRRule
import org.tasks.repeats.RecurrenceUtils.newRecur
import org.tasks.time.DateTime.UTC
import org.tasks.time.DateTimeUtils.startOfDay
import org.tasks.time.DateTimeUtils.startOfMinute
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.text.ParseException
import java.util.*
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@Suppress("ClassName")
class iCalendar @Inject constructor(
        private val tagDataDao: TagDataDao,
        private val preferences: Preferences,
        private val locationDao: LocationDao,
        private val workManager: WorkManager,
        private val geofenceApi: GeofenceApi,
        private val taskCreator: TaskCreator,
        private val tagDao: TagDao,
        private val taskDao: TaskDao,
        private val caldavDao: CaldavDao) {

    suspend fun setPlace(taskId: Long, geo: Geo?) {
        if (geo == null) {
            locationDao.getActiveGeofences(taskId).forEach {
                locationDao.delete(it.geofence)
                geofenceApi.update(it.place)
            }
            return
        }
        var place: Place? = locationDao.findPlace(
                geo.latitude.toLikeString(),
                geo.longitude.toLikeString())
        if (place == null) {
            place = Place.newPlace(geo)
            place.id = locationDao.insert(place)
            workManager.reverseGeocode(place)
        }
        val existing = locationDao.getGeofences(taskId)
        if (existing == null) {
            val geofence = Geofence(place.uid, preferences)
            geofence.task = taskId
            geofence.id = locationDao.insert(geofence)
        } else if (place != existing.place) {
            val geofence = existing.geofence
            geofence.place = place.uid
            locationDao.update(geofence)
            geofenceApi.update(existing.place)
        }
        geofenceApi.update(place)
    }

    suspend fun getTags(categories: List<String>): List<TagData> {
        if (categories.isEmpty()) {
            return emptyList()
        }
        val tags = tagDataDao.getTags(categories).toMutableList()
        val existing = tags.map(TagData::name)
        val toCreate = categories subtract existing
        for (name in toCreate) {
            val tag = TagData(name)
            tagDataDao.createNew(tag)
            tags.add(tag)
        }
        return tags
    }

    suspend fun toVtodo(caldavTask: CaldavTask, task: com.todoroo.astrid.data.Task): ByteArray {
        var remoteModel: Task? = null
        try {
            if (!isNullOrEmpty(caldavTask.vtodo)) {
                remoteModel = fromVtodo(caldavTask.vtodo!!)
            }
        } catch (e: java.lang.Exception) {
            Timber.e(e)
        }
        if (remoteModel == null) {
            remoteModel = Task()
        }

        toVtodo(caldavTask, task, remoteModel)

        val os = ByteArrayOutputStream()
        remoteModel.write(os)
        return os.toByteArray()
    }

    suspend fun toVtodo(caldavTask: CaldavTask, task: com.todoroo.astrid.data.Task, remoteModel: Task) {
        remoteModel.applyLocal(caldavTask, task)
        val categories = remoteModel.categories
        categories.clear()
        categories.addAll(tagDataDao.getTagDataForTask(task.id).map { it.name!! })
        if (isNullOrEmpty(caldavTask.remoteId)) {
            val caldavUid = UUIDHelper.newUUID()
            caldavTask.remoteId = caldavUid
            remoteModel.uid = caldavUid
        } else {
            remoteModel.uid = caldavTask.remoteId
        }
        val location = locationDao.getGeofences(task.id)
        val localGeo = toGeo(location)
        if (localGeo == null || !localGeo.equalish(remoteModel.geoPosition)) {
            remoteModel.geoPosition = localGeo
        }
    }

    suspend fun fromVtodo(
            calendar: CaldavCalendar,
            existing: CaldavTask?,
            remote: Task,
            vtodo: String?,
            obj: String? = null,
            eTag: String? = null) {
        val task = existing?.task?.let { taskDao.fetch(it) }
                ?: taskCreator.createWithValues("").apply {
                    taskDao.createNew(this)
                    existing?.task = id
                }
        val caldavTask = existing ?: CaldavTask(task.id, calendar.uuid, remote.uid, obj)
        task.applyRemote(remote)
        setPlace(task.id, remote.geoPosition)
        tagDao.applyTags(task, tagDataDao, getTags(remote.categories))
        task.suppressSync()
        task.suppressRefresh()
        taskDao.save(task)
        caldavTask.vtodo = vtodo
        caldavTask.etag = eTag
        caldavTask.lastSync = task.modificationDate
        caldavTask.remoteParent = remote.parent
        caldavTask.order = remote.order
        if (caldavTask.id == com.todoroo.astrid.data.Task.NO_ID) {
            caldavTask.id = caldavDao.insert(caldavTask)
            Timber.d("NEW %s", caldavTask)
        } else {
            caldavDao.update(caldavTask)
            Timber.d("UPDATE %s", caldavTask)
        }
    }

    companion object {
        private const val APPLE_SORT_ORDER = "X-APPLE-SORT-ORDER"
        private const val OC_HIDESUBTASKS = "X-OC-HIDESUBTASKS"
        private const val MOZ_SNOOZE_TIME = "X-MOZ-SNOOZE-TIME"
        private const val MOZ_LASTACK = "X-MOZ-LASTACK"
        private const val HIDE_SUBTASKS = "1"
        private val IS_PARENT = { r: RelatedTo ->
            r.parameters.getParameter<RelType>(Parameter.RELTYPE).let {
                it === RelType.PARENT || it == null || it.value.isNullOrBlank()
            }
        }

        private val IS_APPLE_SORT_ORDER = { x: Property? -> x?.name.equals(APPLE_SORT_ORDER, true) }
        private val IS_OC_HIDESUBTASKS = { x: Property? -> x?.name.equals(OC_HIDESUBTASKS, true) }
        private val IS_MOZ_SNOOZE_TIME = { x: Property? -> x?.name.equals(MOZ_SNOOZE_TIME, true) }
        private val IS_MOZ_LASTACK = { x: Property? -> x?.name.equals(MOZ_LASTACK, true) }

        fun Due?.apply(task: com.todoroo.astrid.data.Task) {
            task.dueDate = when (this?.date) {
                null -> 0
                is DateTime -> com.todoroo.astrid.data.Task.createDueDate(
                        URGENCY_SPECIFIC_DAY_TIME,
                        getLocal(this)
                )
                else -> com.todoroo.astrid.data.Task.createDueDate(
                        URGENCY_SPECIFIC_DAY,
                        getLocal(this)
                )
            }
        }

        fun DtStart?.apply(task: com.todoroo.astrid.data.Task) {
            task.hideUntil = when (this?.date) {
                null -> 0
                is DateTime -> task.createHideUntil(HIDE_UNTIL_SPECIFIC_DAY_TIME, getLocal(this))
                else -> task.createHideUntil(HIDE_UNTIL_SPECIFIC_DAY, getLocal(this))
            }
        }

        @JvmStatic
        fun getLocal(property: DateProperty): Long =
                org.tasks.time.DateTime.from(property.date)?.toLocal()?.millis ?: 0

        fun fromVtodo(vtodo: String): Task? {
            try {
                val tasks = tasksFromReader(StringReader(vtodo))
                if (tasks.size == 1) {
                    return tasks[0]
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
            return null
        }

        var Task.parent: String?
            get() = relatedTo.find(IS_PARENT)?.value
            set(value) {
                val parents = relatedTo.filter(IS_PARENT)
                when {
                    value.isNullOrBlank() -> relatedTo.removeAll(parents)
                    parents.isEmpty() -> relatedTo.add(RelatedTo(value))
                    else -> {
                        if (parents.size > 1) {
                            relatedTo.removeAll(parents.drop(1))
                        }
                        parents[0].let {
                            it.value = value
                            it.parameters.replace(RelType.PARENT)
                        }
                    }
                }
            }

        var Task.order: Long?
            get() = unknownProperties.find(IS_APPLE_SORT_ORDER).let { it?.value?.toLongOrNull() }
            set(order) {
                if (order == null) {
                    unknownProperties.removeIf(IS_APPLE_SORT_ORDER)
                } else {
                    unknownProperties
                            .find(IS_APPLE_SORT_ORDER)
                            ?.let { it.value = order.toString() }
                            ?: unknownProperties.add(XProperty(APPLE_SORT_ORDER, order.toString()))
                }
            }

        var Task.collapsed: Boolean
            get() = unknownProperties.find(IS_OC_HIDESUBTASKS).let { it?.value == HIDE_SUBTASKS }
            set(collapsed) {
                if (collapsed) {
                    unknownProperties
                            .find(IS_OC_HIDESUBTASKS)
                            ?.let { it.value = HIDE_SUBTASKS }
                            ?: unknownProperties.add(XProperty(OC_HIDESUBTASKS, HIDE_SUBTASKS))
                } else {
                    unknownProperties.removeIf(IS_OC_HIDESUBTASKS)
                }
            }

        var Task.snooze: Long?
            get() = unknownProperties.find(IS_MOZ_SNOOZE_TIME)?.value?.let {
                org.tasks.time.DateTime.from(DateTime(it)).toLocal().millis
            }
            set(value) {
                value
                        ?.toDateTime()
                        ?.takeIf { it.isAfterNow }
                        ?.toUTC()
                        ?.let { DateTime(true).apply { time = it.millis } }
                        ?.let { utc ->
                            unknownProperties.find(IS_MOZ_SNOOZE_TIME)
                                    ?.let { it.value = utc.toString() }
                                    ?: unknownProperties.add(
                                            XProperty(MOZ_SNOOZE_TIME, utc.toString())
                                    )
                            val lastAck = DateTime(true).apply { time = lastModified!! }
                            unknownProperties.find(IS_MOZ_LASTACK)
                                    ?.let { it.value = lastAck.toString() }
                                    ?: unknownProperties.add(
                                            XProperty(MOZ_LASTACK, lastAck.toString())
                                    )
                        }
                        ?: unknownProperties.removeIf(IS_MOZ_SNOOZE_TIME)
            }

        fun com.todoroo.astrid.data.Task.applyRemote(remote: Task) {
            val completedAt = remote.completedAt
            if (completedAt != null) {
                completionDate = getLocal(completedAt)
            } else if (remote.status === Status.VTODO_COMPLETED) {
                if (!isCompleted) {
                    completionDate = DateUtilities.now()
                }
            } else {
                completionDate = 0L
            }
            remote.createdAt?.let {
                creationDate = newDateTime(it, UTC).toLocal().millis
            }
            title = remote.summary
            notes = remote.description
            priority = when (remote.priority) {
                // https://tools.ietf.org/html/rfc5545#section-3.8.1.9
                in 1..4 -> com.todoroo.astrid.data.Task.Priority.HIGH
                5 -> com.todoroo.astrid.data.Task.Priority.MEDIUM
                in 6..9 -> com.todoroo.astrid.data.Task.Priority.LOW
                else -> com.todoroo.astrid.data.Task.Priority.NONE
            }
            setRecurrence(remote.rRule?.recur)
            remote.due.apply(this)
            remote.dtStart.apply(this)
            isCollapsed = remote.collapsed
            reminderSnooze = remote.snooze ?: 0
        }

        fun Task.applyLocal(caldavTask: CaldavTask, task: com.todoroo.astrid.data.Task) {
            createdAt = newDateTime(task.creationDate).toUTC().millis
            summary = task.title
            description = task.notes
            val allDay = !task.hasDueTime() && !task.hasStartTime()
            val dueDate = if (task.hasDueTime()) task.dueDate else task.dueDate.startOfDay()
            var startDate = if (task.hasStartTime()) {
                task.hideUntil.startOfMinute()
            } else {
                task.hideUntil.startOfDay()
            }
            due = if (dueDate > 0) {
                startDate = min(dueDate, startDate)
                Due(if (allDay) getDate(dueDate) else getDateTime(dueDate))
            } else {
                null
            }
            dtStart = if (startDate > 0) {
                DtStart(if (allDay) getDate(startDate) else getDateTime(startDate))
            } else {
                null
            }
            if (task.isCompleted) {
                completedAt = Completed(DateTime(task.completionDate))
                status = Status.VTODO_COMPLETED
                percentComplete = 100
            } else if (completedAt != null) {
                completedAt = null
                status = null
                percentComplete = null
            }
            rRule = if (task.isRecurring) {
                try {
                    val recur = newRecur(task.recurrence!!)
                    val repeatUntil = task.repeatUntil
                    recur.until = if (repeatUntil > 0) {
                        DateTime(newDateTime(repeatUntil).toUTC().millis)
                    } else {
                        null
                    }
                    newRRule(recur.toString())
                } catch (e: ParseException) {
                    Timber.e(e)
                    null
                }
            } else {
                null
            }
            lastModified = newDateTime(task.modificationDate).toUTC().millis
            priority = when (task.priority) {
                com.todoroo.astrid.data.Task.Priority.NONE -> 0
                com.todoroo.astrid.data.Task.Priority.MEDIUM -> 5
                com.todoroo.astrid.data.Task.Priority.HIGH ->
                    if (priority < 5) max(1, priority) else 1
                else -> if (priority > 5) min(9, priority) else 9
            }
            parent = if (task.parent == 0L) null else caldavTask.remoteParent
            order = caldavTask.order
            collapsed = task.isCollapsed
            snooze = task.reminderSnooze
        }

        private fun getDate(timestamp: Long): Date {
            return Date(timestamp + newDateTime(timestamp).offset)
        }

        private fun getDateTime(timestamp: Long): DateTime {
            val tz = ical4jTimeZone(TimeZone.getDefault().id)
            val dateTime = DateTime(if (tz != null) timestamp else org.tasks.time.DateTime(timestamp).toUTC().millis)
            dateTime.timeZone = tz
            return dateTime
        }
    }
}