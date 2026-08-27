# CorrectedCamera for Winlator

Programma Windows nativo x64 pensato per essere eseguito dentro Winlator.

## Cosa fa questa prima build

- legge lo stream MJPEG prodotto da CorrectedCamera Android;
- mostra il video corretto in una finestra Windows;
- non richiede .NET;
- viene compilato con runtime C++ statico per ridurre le dipendenze.

Indirizzo predefinito:

    http://192.168.108.137:8080/video

Se l'IP del tablet cambia, inserire l'indirizzo mostrato da CorrectedCamera Android.

## Importante

Questa prima build verifica il collegamento Android -> Winlator con un EXE nativo.
Non registra ancora una webcam DirectShow. La registrazione di una vera camera
virtuale dentro Wine/Winlator deve essere aggiunta solo dopo aver verificato che
questo EXE riceva correttamente lo stream.

## Build

Il workflow GitHub Actions `.github/workflows/windows-winlator.yml` produce
l'artifact `CorrectedCamera-Winlator` contenente:

    CorrectedCamera-Winlator.exe
