# Stato del progetto — revisione stabilità 0.3.0-test

## Obiettivo

Evitare che la sorgente di Corrected Camera si blocchi o resti nera durante l'uso del cerchio/condivisione, e distinguere questi problemi dalla videocamera nativa della chiamata. Il video della studentessa resta visibile all'utente: il difetto segnalato è sul suo video in uscita.

## Idee e funzioni

Mantenuti camera anteriore/posteriore, rotazione, foto, video locale, registrazione schermo, overlay tondo/quadrato e stream MJPEG. Aggiunti arresto esplicito, indicazioni camera occupata/stallo e passaggio Activity/servizio controllato.

## Design

Interfaccia esistente conservata. Variante installabile accanto all'originale, con nome Test per identificarla. Il messaggio di errore sull'overlay non dichiara che il video sia ancora attivo quando non arrivano immagini. Dicitura “Creato da Dimitri Azzarone” nella notifica del servizio.

## Tecnologie

Kotlin, CameraX 1.5.3 ImageAnalysis RGBA e VideoCapture, foreground service camera, WindowManager overlay, MediaProjection/MediaRecorder, JPEG e server MJPEG. Analisi preferita 640×480 e produzione limitata a circa 15 fps. Nessun nuovo driver virtuale o accesso root.

## File

- `FrameBuffer.kt`: copia buffer rispettando stride/limite, conversione canali, coda a un elemento e limitatore fps.
- `BitmapFrames.kt`: costruzione/rotazione bitmap e presentazione con coda limitata.
- `MainActivity.kt`: possesso dei propri use case, invalidazione callback, trasferimento al servizio prima di andare in background, arresto.
- `FloatingCameraService.kt`: acquisizione persistente, aggiornamento impostazioni senza interferenze dell'Activity, stato camera e notifica.
- `CameraStreamHub.kt`: generazioni delle sorgenti e rifiuto di fotogrammi di produttori precedenti.
- `ScreenCaptureService.kt`: callback di revoca MediaProjection.
- Test buffer e grafica nativa; workflow di compilazione e controllo; README di prova.

## Problemi riscontrati nel codice

1. I controlli dell'Activity potevano invocare `unbindAll()` e scollegare anche la camera del servizio. Il servizio conservava `cameraProvider != null` e non rifaceva il bind: un percorso coerente con un cerchio fermo/nero dopo il ritorno ai controlli.
2. `moveTaskToBack()` precedeva la callback asincrona che avviava il foreground service camera. Sui sistemi recenti questo può violare i requisiti del permesso camera durante l'uso.
3. Riciclo esplicito del precedente bitmap ImageView mentre il renderer hardware poteva ancora usarlo; fotogrammi UI non limitati nell'Activity e conversioni ripetute anche quando l'output non veniva pubblicato.
4. Eccezioni di elaborazione non gestite nell'analyzer del servizio; copia del buffer fondata su un'intera ultima riga di padding.
5. Camera in uso da altre app o assenza di frame non erano spiegate nella finestra flottante.
6. Mancava la callback MediaProjection richiesta dalle versioni recenti di Android.

Questi sono difetti/rischi del codice corretti; non dimostrano quale di essi abbia causato la specifica chiamata dell'utente. L'ipotesi iniziale di alias fra bitmap sorgente e ritaglio non è usata come diagnosi certa: il comportamento dipende anche dalla mutabilità del bitmap.

## Soluzioni e decisioni

Rilasciare soltanto risorse proprie, ignorare callback e frame di generazioni precedenti, avviare il servizio mentre l'app è visibile e attendere il primo frame, limitare il lavoro prima della conversione, non riciclare bitmap già mostrati e gestire gli errori senza uccidere l'analyzer. CameraX gestisce la riapertura quando la camera torna disponibile; non introdotta una lotta di riavvii contro l'app di chiamata.

APK Test separata dall'originale, evitando di richiedere la disinstallazione o presumere la disponibilità della vecchia chiave debug. Prima di usare Test, arrestare il servizio della vecchia app per non creare un conflitto aggiuntivo e occupare la stessa porta 8080.

## Questioni aperte

- Modello del tablet, Android/firmware e versione dell'app di chiamata.
- Se l'immagine inviata passa dal cerchio condiviso, dallo stream su un altro dispositivo o dalla normale fotocamera WhatsApp.
- Se il cerchio si ferma già localmente oppure solo presso la destinataria.
- Rotazione WhatsApp: immagine realmente ruotata di 180° o soltanto anteprima specchiata; comportamento nelle altre app camera.
- Compatibilità della condivisione schermo intero con gli overlay su quel sistema e comportamento della camera concorrente.
- Prova continuativa, consumo/temperatura e passaggi fra app durante una vera lezione.

## Verifiche locali

Dieci test passati: sette sul buffer/coda/ritmo e tre di grafica nativa su pixel, rotazione e gestione dei bitmap. Compilazione Kotlin/Java Android riuscita con JDK 17; controllo statico Android senza errori. Rimangono avvisi non bloccanti, in parte già presenti nella base.

## Compilazione e consegna verificate

Il workflow Android è riuscito il 13 novembre 2026: https://github.com/DimitriAzzarone/CorrectedCamera/actions/runs/43434047472 . Test e controllo statico passati; step Build debug APK riuscito e artifact CorrectedCamera-debug presente. Codice compilato: b7e4460ed56a5e516f5281c8921d61a183a429ae.

Due esecuzioni precedenti si erano fermate sulla configurazione dei test: memoria consumata dal vecchio Jetifier su una dipendenza nativa Robolectric e directory home del resolver mancante in un runner pulito. Rimossi il trasformatore non necessario, limitati memoria/worker Gradle e creata la directory di test prima dell'avvio. Verificata anche l'esecuzione locale dei dieci test senza cache della home di Robolectric.

L'APK di prova è stata firmata con una chiave stabile separata dalla vecchia app, controllata con apksig e caricata in Programmazione su Drive. Identificativo ch.formazione.correctedcamera.stability, versione 0.3.0-test, codice 3. Non è un aggiornamento della vecchia installazione. Nessuna prova hardware o chiamata reale eseguita da questo ambiente.

## Alternative richieste

Esaminati i componenti Windows già nel repository. Il ricevitore MJPEG e la camera virtuale DirectShow sono due progetti con sorgenti diverse: il ponte fra loro è una possibilità futura, non una funzione implementata in questa APK. Vedere [OPZIONI_ANDROID_WINLATOR_WHATSAPP.md](OPZIONI_ANDROID_WINLATOR_WHATSAPP.md).

## Prossimo passo

Prova sul tablet in due fasi: sorgente locale stabile e chiamata/condivisione reale, dopo aver arrestato la vecchia app. Non dichiarare risolto il video ricevuto dalla studentessa finché questa verifica non è stata eseguita.
