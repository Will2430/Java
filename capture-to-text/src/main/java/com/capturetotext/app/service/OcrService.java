package com.capturetotext.app.service;

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

@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final ITesseract tesseract;

    public OcrService() {
        this.tesseract = new Tesseract();
        // Tess4J bundles native Tesseract/Leptonica binaries + eng.traineddata
        // inside its jar; extractTessResources unpacks them to a temp dir once.
        // setDatapath must point AT the "tessdata" folder itself (not its parent) —
        // tess4j 5.19.0's Tesseract.init() looks for "<datapath>/eng.traineddata" directly.
        tesseract.setDatapath(LoadLibs.extractTessResources("tessdata").getAbsolutePath());
        tesseract.setLanguage("eng");
    }

    public OcrResult extractText(File imageFile) throws TesseractException {
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
            List<Word> words = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
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
