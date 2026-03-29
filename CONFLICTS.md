# Conflictanalyse — RandomRingtone

Versie: 0.4.0 "Amy_Winehouse"
Datum: 2026-03-29

---

## 1. Alle manieren waarop een ringtone kan worden ingesteld

| # | Actie | Waar in UI | Technisch mechanisme | DB schrijft | System API |
|---|-------|-----------|---------------------|-------------|------------|
| A | Direct vanuit zoekresultaat | Zoeken → "Ringtone" knop | `RingtoneManager.setAsRingtone()` | Geen | `RingtoneManager.TYPE_RINGTONE` + MediaStore |
| B | Direct vanuit editor | Editor → "Ringtone" knop | `AudioTrimmer.trim()` → `RingtoneManager.setAsRingtone()` | Geen | `RingtoneManager.TYPE_RINGTONE` + MediaStore |
| C | Vanuit bibliotheek (Ringtones tab) | Bibliotheek → "Instellen als..." → Telefoon | `RingtoneManager.setAsRingtone()` | Geen | `RingtoneManager.TYPE_RINGTONE` + MediaStore |
| D | Vanuit bibliotheek → Notificatie | Bibliotheek → "Instellen als..." → Notificatie | `RingtoneManager.setAsNotification()` | Geen | `RingtoneManager.TYPE_NOTIFICATION` + MediaStore |
| E | Vanuit bibliotheek → SMS | Bibliotheek → "Instellen als..." → SMS | `saveGlobalAssignment()` | `assignments` + `saved_tracks` | Geen (NotificationService luistert) |
| F | Vanuit bibliotheek → WhatsApp | Bibliotheek → "Instellen als..." → WhatsApp | `saveGlobalAssignment()` | `assignments` + `saved_tracks` | Geen (NotificationService luistert) |
| G | Via playlist (FIXED modus) | Playlists → playlist met mode=FIXED | Eerste track uit playlist | `playlists` + `playlist_tracks` | Afhankelijk van channel |
| H | Via playlist (RANDOM, EVERY_CALL) | Automatisch bij inkomend/uitgaand | `NotificationService` of `RingtoneWorker` | `playlists.lastPlayedTrackId` | Afhankelijk van channel |
| I | Via playlist (RANDOM, tijdschema) | Automatisch op interval | `RingtoneWorker` via WorkManager | `playlists.lastPlayedTrackId` | Afhankelijk van channel |
| J | Per-contact telefoon | Playlists → contact-gekoppeld, channel=CALL | `ContactsRepository.setContactRingtone()` | `playlists` | `ContactsContract.CUSTOM_RINGTONE` |
| K | Legacy assignment (oud systeem) | AssignmentScreen (nog in codebase) | Diverse | `assignments` tabel | Diverse |

---

## 2. Conflicten geïdentificeerd

### CONFLICT 1: Dubbele instelling telefoon-ringtone (KRITIEK)

**Scenario:** User stelt via zoekresultaat (A) een ringtone in. Daarna maakt user een playlist (H/I) die ook CALL als trigger heeft.

**Wat gebeurt er:**
- Actie A schrijft naar `RingtoneManager.TYPE_RINGTONE` (system-level)
- Playlist H/I overschrijft dit periodiek via `RingtoneWorker` → `setAsRingtone()`
- User begrijpt niet waarom de ringtone steeds verandert

**Oplossing:**
- **Functioneel:** Wanneer een playlist actief is voor CALL, toon waarschuwing bij direct instellen (A/B/C): "Let op: playlist 'Rock Party' is actief voor telefoon en zal deze ringtone overschrijven"
- **Technisch:** Centrale `ConflictResolver` die checkt of er actieve playlists zijn voor hetzelfde kanaal vóór elke directe instelling
- **UI:** Overview tab toont expliciet welke instelling op dit moment geldt

### CONFLICT 2: Meerdere playlists op hetzelfde kanaal (KRITIEK)

**Scenario:** User maakt playlist "Rock" met channel=CALL + playlist "Jazz" met channel=CALL, beide globaal en actief.

**Wat gebeurt er:**
- Beide playlists matchen. `PlaylistDao.getActiveGlobalForChannel(CALL)` retourneert een lijst.
- Onduidelijk welke wint.

**Oplossing:**
- **Functioneel:** Maximaal 1 actieve playlist per kanaal+scope combinatie. Bij activeren van playlist "Jazz" voor CALL globaal → automatisch "Rock" deactiveren, met melding.
- **Technisch:** `PlaylistDao` enforce: `UPDATE playlists SET isActive = 0 WHERE channel = :channel AND contactUri IS NULL AND id != :newActiveId`
- **UI:** Overview toont per kanaal welke playlist actief is

### CONFLICT 3: Globaal vs. per-contact (MEDIUM)

**Scenario:** Globale playlist voor CALL actief + per-contact playlist voor "Joyce" op CALL actief.

**Wat zou moeten gebeuren:** Per-contact gaat voor. Bij bellen met Joyce → Joyce's playlist. Bij bellen met iemand anders → globale playlist.

**Huidig probleem:**
- Voor CALL: `ContactsContract.CUSTOM_RINGTONE` (per-contact) overschrijft automatisch de system ringtone → CORRECT gedrag
- Voor SMS/WhatsApp: `NotificationService.findAssignment()` zoekt eerst per-contact, dan fallback globaal → CORRECT gedrag
- Maar `RingtoneWorker` overschrijft de system ringtone periodiek → kan per-contact CALL ringtone "wegspoelen" als worker ContactsContract niet respecteert

**Oplossing:**
- **Technisch:** `RingtoneWorker` moet bij CALL channel checken of er per-contact assignments actief zijn en die NIET overschrijven
- **Functioneel:** Overview toont per-contact overrides duidelijk

### CONFLICT 4: Legacy assignments vs. nieuwe playlists (MEDIUM)

**Scenario:** Oude `assignments` tabel bevat entries. Nieuwe `playlists` tabel bevat entries. Beide worden door `NotificationService` en `RingtoneWorker` gelezen.

**Wat gebeurt er:**
- `NotificationService` leest alleen `assignmentDao().getAll()` → mist playlists
- `RingtoneWorker` leest alleen `assignmentDao().getAll()` → mist playlists

**Oplossing:**
- **Technisch:** Migreer NotificationService en RingtoneWorker naar `PlaylistDao` als primaire bron
- **Legacy:** `AssignmentScreen.kt` verwijderen, `assignments` tabel deprecaten
- **Migratie:** Bij app-start: converteer bestaande assignments naar playlists

### CONFLICT 5: EVERY_CALL niet geïmplementeerd (BUG)

**Scenario:** Playlist met schedule=EVERY_CALL. User verwacht na elk gesprek een andere ringtone.

**Wat gebeurt er:** Niets. `lastPlayedTrackId` wordt nooit bijgewerkt. Random kan dezelfde track kiezen.

**Oplossing:**
- **Technisch:** `PhoneStateListener` of `TelecomManager` callback die na elk gesprek de playlist triggert
- **Dedup:** Filter `lastPlayedTrackId` uit random selectie. Bij playlist van 1 track: geen wissel
- **Update:** Na wissel: `playlistDao.update(playlist.copy(lastPlayedTrackId = newTrackId))`

### CONFLICT 6: NotificationService speelt geluid + origineel geluid (MEDIUM)

**Scenario:** SMS binnenkomst. NotificationService speelt custom geluid. Maar het originele SMS-geluid speelt ook.

**Wat gebeurt er:** `cancelNotificationSound()` doet `snoozeNotification(key, 1)` — werkt niet betrouwbaar op alle Android versies/Samsung OneUI.

**Oplossing:**
- **Technisch:** Gebruik `NotificationChannel` manipulatie: zet het geluid van het SMS-kanaal op stil wanneer onze service actief is
- **Alternatief:** DND (Do Not Disturb) filter — verberg specifieke app-notificaties en post eigen notificatie met custom geluid
- **Samsung-specifiek:** Test op OneUI 8 — Samsung kan extra beperkingen hebben

### CONFLICT 7: Offline / download failure bij WorkManager (LOW)

**Scenario:** WorkManager triggert, maar device is offline. Track moet gedownload worden.

**Wat gebeurt er:** Worker heeft `NetworkType.CONNECTED` constraint, dus start niet zonder netwerk. Maar als netwerk wegvalt tijdens download → crash.

**Oplossing:**
- **Technisch:** Try-catch in worker + `Result.retry()` bij network failure
- **Pre-download:** Bij playlist activeren: pre-download alle tracks zodat offline ook werkt
- **Constraint:** Worker retried automatisch met exponential backoff

---

## 3. Conflictresolutie-hiërarchie

Wanneer meerdere instellingen hetzelfde kanaal raken, geldt deze prioriteit:

```
1. Per-contact playlist (hoogste prioriteit)
2. Globale playlist (actief)
3. Directe instelling (A/B/C/D — "handmatig vastgezet")
4. Legacy assignment (laagste, wordt gemigreerd)
```

Regel: **specifiekere instelling wint altijd**.

---

## 4. Oplossingsarchitectuur: Centrale ConflictResolver + Overview

### ConflictResolver (nieuw component)

```kotlin
class ConflictResolver(private val db: RingtoneDatabase) {

    data class ActiveSetting(
        val channel: Channel,
        val source: SettingSource,
        val sourceName: String,        // Playlist naam of "Handmatig"
        val trackTitle: String?,
        val contactName: String?,      // null = globaal
        val schedule: Schedule?,
        val isConflicting: Boolean     // true als overschreven door hogere prioriteit
    )

    enum class SettingSource {
        CONTACT_PLAYLIST,  // Prioriteit 1
        GLOBAL_PLAYLIST,   // Prioriteit 2
        MANUAL,            // Prioriteit 3
        LEGACY_ASSIGNMENT  // Prioriteit 4
    }

    suspend fun getActiveSettings(): List<ActiveSetting>
    suspend fun checkConflicts(channel: Channel, contactUri: String?): List<String>
    suspend fun enforceOneActivePerChannelScope(playlistId: Long)
}
```

### Overview Tab (nieuw scherm)

Toont per kanaal de actieve instelling en eventuele conflicten:

```
┌─ Overview ───────────────────────────────────────┐
│                                                   │
│  HUIDIGE INSTELLINGEN                             │
│                                                   │
│  📞 Telefoon (globaal)                           │
│  ├─ Actief: Playlist "Rock Party" (Random)       │
│  ├─ Schema: Bij elk gesprek                      │
│  └─ Huidige track: AC/DC - Thunderstruck         │
│                                                   │
│  📞 Telefoon — Joyce                             │
│  ├─ Actief: Playlist "Liefde" (Random)           │
│  ├─ Schema: Dagelijks                            │
│  └─ Huidige track: Ed Sheeran - Perfect          │
│                                                   │
│  🔔 Systeemmelding (globaal)                     │
│  ├─ Actief: Handmatig ingesteld                  │
│  └─ Track: Bicep - Glue                          │
│                                                   │
│  💬 SMS (globaal)                                │
│  ├─ Actief: Playlist "Chill" (Random)            │
│  ├─ Schema: Elk uur                              │
│  └─ Huidige track: Bonobo - Kerala               │
│                                                   │
│  📱 WhatsApp (globaal)                           │
│  └─ Niet ingesteld — systeem standaard           │
│                                                   │
│  ⚠️ CONFLICTEN                                   │
│  └─ Geen conflicten gevonden                     │
│                                                   │
└───────────────────────────────────────────────────┘
```

---

## 5. Benodigde refactoring (prioriteit)

| # | Actie | Impact | Prioriteit |
|---|-------|--------|-----------|
| R1 | `ConflictResolver` bouwen | Centraal conflictbeheer | HOOG |
| R2 | Overview tab bouwen | User ziet wat er actief is | HOOG |
| R3 | NotificationService migreren naar PlaylistDao | Playlists werken voor SMS/WA | HOOG |
| R4 | RingtoneWorker migreren naar PlaylistDao | Playlists werken voor schema | HOOG |
| R5 | EVERY_CALL implementeren | PhoneStateListener + lastPlayedTrackId | HOOG |
| R6 | Enforce 1-actief-per-kanaal+scope | Voorkom dubbele playlists | HOOG |
| R7 | Legacy AssignmentScreen verwijderen | Verwijder verwarring | MEDIUM |
| R8 | TrackResolver extracten | Dedup T4/T5 code | MEDIUM |
| R9 | OkHttpClient singleton | Performance | LOW |
| R10 | Pre-download bij playlist activering | Offline support | LOW |
