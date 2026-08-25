# Corrected Camera — prototipo 0.1

Obiettivo del primo milestone:

**fotocamera Android → correzione rotazione → JPEG → stream MJPEG HTTP locale**

Questo progetto NON dichiara ancora una camera virtuale Android e quindi Meet/Zoom
non vedranno automaticamente lo stream come sorgente camera.

## Cosa fa già

- CameraX
- camera anteriore/posteriore
- rotazione manuale 0/90/180/270
- stream MJPEG su porta 8080
- pagina HTML di test su `http://IP_TABLET:8080/`
- endpoint diretto `http://IP_TABLET:8080/video`
- build automatica APK con GitHub Actions

## Termux

Dopo aver estratto/coperto il progetto:

```bash
cd CorrectedCamera
git init
git add .
git commit -m "Initial Corrected Camera prototype"
```

Poi crea/collega il repository GitHub e fai push. La build è affidata a GitHub Actions.

## Verifica reale

Non considerare la build riuscita solo perché i file esistono.

È riuscita soltanto quando:

1. GitHub Actions mostra il job verde.
2. Lo step `Build debug APK` è riuscito.
3. Negli Artifacts esiste `CorrectedCamera-debug`.
4. L'APK si installa davvero sul tablet.
5. Aprendo `http://IP_TABLET:8080/` da un altro dispositivo sulla stessa rete appare il video corretto.

## Prossimo milestone

Dopo la verifica del prototipo:

- ottimizzare la rotazione con GPU/OpenGL invece di Bitmap/JPEG CPU;
- aggiungere mirror e crop;
- testare latenza/FPS;
- verificare root/kernel/HAL/UVC del tablet;
- scegliere il ponte verso una camera realmente enumerata da Android.
