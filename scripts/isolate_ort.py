"""Retain sherpa native runtime; isolate Java ORT SONAME plus its JNI dependency."""
import zipfile,subprocess,tempfile,pathlib
with zipfile.ZipFile('/tmp/ort-java-source.aar') as src, zipfile.ZipFile('app/libs/onnxruntime-java-jni.aar','w',zipfile.ZIP_DEFLATED) as dst:
    for item in src.infolist():
        name=item.filename;data=src.read(name)
        if name.endswith('/libonnxruntime.so') or name.endswith('/libonnxruntime4j_jni.so'):
            with tempfile.TemporaryDirectory() as d:
                p=pathlib.Path(d)/'lib.so';p.write_bytes(data)
                if name.endswith('/libonnxruntime.so'):
                    subprocess.run(['patchelf','--set-soname','libvoicebeam_ort.so',str(p)],check=True)
                    name=name.replace('libonnxruntime.so','libvoicebeam_ort.so')
                else:
                    subprocess.run(['patchelf','--replace-needed','libonnxruntime.so','libvoicebeam_ort.so',str(p)],check=True)
                data=p.read_bytes()
        dst.writestr(name,data)
