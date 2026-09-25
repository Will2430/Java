package com.capturetotext.worker.service;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;
import net.sourceforge.tess4j.util.LoadLibs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
 
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Moved verbatim from the API module (Phase 1) -- OCR now only happens here.
 * See CLAUDE.md's "Tess4J version-specific gotchas" section for why the two
 * deviations below (LoadLibs import path, setDatapath argument) matter and
 * must be re-verified if tess4j.version is ever bumped.
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    // One ITesseract instance per thread, not a shared singleton field --
    // Tess4J wraps a native (JNA) engine handle that isn't safe to call
    // concurrently from multiple threads. With @KafkaListener(concurrency="3")
    // running 3 consumer threads in this one process, each thread lazily
    // gets its own private native instance the first time it calls .get(),
    // so no two threads ever touch the same native memory.
    private final ThreadLocal<ITesseract> tesseractThreadLocal = ThreadLocal.withInitial(this::createTesseract);

    private ITesseract createTesseract() {
        ITesseract tesseract = new Tesseract();
        // Tess4J bundles native Tesseract/Leptonica binaries + eng.traineddata
        // inside its jar; extractTessResources unpacks them to a temp dir once.
        // setDatapath must point AT the "tessdata" folder itself (not its parent) --
        // tess4j 5.19.0's Tesseract.init() looks for "<datapath>/eng.traineddata" directly.
        tesseract.setDatapath(LoadLibs.extractTessResources("tessdata").getAbsolutePath());
        tesseract.setLanguage("eng");
        log.info("Initialized a new Tesseract engine instance for thread {}", Thread.currentThread().getName());
        return tesseract;
    }

    public OcrResult extractText(File imageFile) throws TesseractException {
        ITesseract tesseract = tesseractThreadLocal.get();
        String text = tesseract.doOCR(imageFile);
        Double confidence = tryComputeConfidence(imageFile);
        return new OcrResult(text, confidence);
    }

    private Double tryComputeConfidence(File imageFile) {
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                return null;
            }
            List<Word> words = tesseractThreadLocal.get().getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            if (words.isEmpty()) {
                return null;
            }
            return words.stream()
                    .mapToDouble(Word::getConfidence)
                    .average()
                    .orElse(0.0);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not compute OCR confidence for {}: {}", imageFile.getName(), e.getMessage());
            return null;
        }
    }
}
