import static qupath.lib.gui.scripting.QPEx.*
import ij.process.AutoThresholder
import qupath.lib.regions.RegionRequest
import qupath.imagej.tools.IJTools
import javax.swing.JOptionPane

// =====================================================
// PARAMETERS (edit if you need)
// =====================================================
def dapiChannel  = 'DAPI'
def drd1Channel  = 'Drd1'
def cckbrChannel = 'Cckbr'
def a2aChannel   = 'A2a'


def downsample = 8.0
def requestedPixelSize = 0.5
def minArea = 10.0
def maxArea = 400.0

// Channel stack indices (ImageJ stack is 1-based)
int IDX_DAPI  = 1
int IDX_DRD1  = 2
int IDX_CCKBR = 3
int IDX_A2A   = 4

// =====================================================
// Helpers
// =====================================================
def server = getCurrentServer()
if (server == null) {
    println "ERROR: No image open."
    return
}

def fullX = 0
def fullY = 0
def fullWidth = server.getWidth()
def fullHeight = server.getHeight()

def getOtsuForStackIndex = { int stackIndex ->
    def req = RegionRequest.createInstance(server.getPath(), downsample, fullX, fullY, fullWidth, fullHeight)
    def pathImg = IJTools.convertToImagePlus(server, req)
    def imp = pathImg.getImage()
    def ip = imp.getStack().getProcessor(stackIndex)
    if (ip.getBitDepth() != 8)
        ip = ip.convertToByte(true)
    return new AutoThresholder().getThreshold("Otsu", ip.getHistogram())
}

def askMultiplier = { String label ->
    String inM = JOptionPane.showInputDialog(null,
            "${label}: input multiplier (default 1.0):",
            "${label} Multiplier", JOptionPane.PLAIN_MESSAGE, null, null, "1.0") as String
    if (inM == null) return null
    double mult = 1.0
    try { mult = Double.parseDouble(inM.trim()) } catch (Exception e) { mult = 1.0 }
    return mult
}

// Store marker positivity as a measurement: 1.0 = positive, 0.0 = negative
def setBinaryMeasurement = { cell, String name, boolean isPos ->
    cell.getMeasurementList().putMeasurement(name, isPos ? 1.0 : 0.0)
}

// Convenience counter for 0/1 measurements
def countPos = { String measName ->
    return getDetectionObjects().count { it.getMeasurementList().getMeasurementValue(measName) == 1.0 }
}

def totalCells = { -> getDetectionObjects().size() }

// =====================================================
// PART 1: DAPI CELL DETECTION (Watershed)
// =====================================================
while (true) {

    double dapiMultiplier = askMultiplier("DAPI")
    if (dapiMultiplier == null) return

    int otsuDapi = getOtsuForStackIndex(IDX_DAPI)
    double thrDapi = otsuDapi * dapiMultiplier
    println "INFO: Computed DAPI Otsu: ${otsuDapi} -> threshold: ${thrDapi}"

    def params = [
            detectionImage            : dapiChannel,
            requestedPixelSizeMicrons : requestedPixelSize,
            backgroundRadiusMicrons   : 8.0,
            backgroundByReconstruction: true,
            medianRadiusMicrons       : 0.0,
            sigmaMicrons              : 1.5,
            minAreaMicrons            : minArea,
            maxAreaMicrons            : maxArea,
            threshold                 : thrDapi,
            watershedPostProcess      : true,
            cellExpansionMicrons      : 5.0,
            includeNuclei             : true,
            smoothBoundaries          : true,
            makeMeasurements          : true
    ]

    def ann = getAnnotationObjects()
    if (ann && ann.size() > 0) {
        ann.each { a ->
            selectObjects([a])
            runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', params)
        }
    } else {
        runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', params)
    }

    fireHierarchyUpdate()
    getCurrentViewer().repaint()
    println "INFO: Detected objects: ${getDetectionObjects().size()}"

    int r = JOptionPane.showConfirmDialog(null,
            "Proceed to marker classification?\nYes: continue\nNo: redo DAPI\nCancel: exit",
            "Confirm DAPI Detection", JOptionPane.YES_NO_CANCEL_OPTION)

    if (r == JOptionPane.YES_OPTION) break
    if (r == JOptionPane.NO_OPTION) {
        removeObjects(getDetectionObjects(), true)
        fireHierarchyUpdate()
        getCurrentViewer().repaint()
        continue
    }
    return
}

// =====================================================
// Compute Otsu for Drd1/Cckbr/A2a once (from downsampled image)
// =====================================================
int otsuDrd1  = getOtsuForStackIndex(IDX_DRD1)
int otsuCckbr = getOtsuForStackIndex(IDX_CCKBR)
int otsuA2a   = getOtsuForStackIndex(IDX_A2A)

println "INFO: Computed Drd1 Otsu: ${otsuDrd1}"
println "INFO: Computed Cckbr Otsu: ${otsuCckbr}"
println "INFO: Computed A2a Otsu: ${otsuA2a}"

// =====================================================
// PART 2: DRD1 CLASSIFICATION (stores Drd1_pos measurement)
// =====================================================
while (true) {
    double mult = askMultiplier("Drd1")
    if (mult == null) return
    double thr = otsuDrd1 * mult
    println "INFO: Effective Drd1 threshold: ${thr}"

    getDetectionObjects().each { cell ->
        double mean = cell.getMeasurementList().getMeasurementValue("Nucleus: Drd1 mean")
        boolean isPos = (mean >= thr)
        setBinaryMeasurement(cell, "Drd1_pos", isPos)
        // Temporary class for visualization during tuning
        cell.setPathClass(isPos ? getPathClass("Drd1+") : getPathClass("Drd1-"))
    }

    fireHierarchyUpdate()
    getCurrentViewer().repaint()

    int tot = totalCells()
    int pos = countPos("Drd1_pos")
    println String.format("INFO: >>> Drd1+ at multiplier %.3f: %d/%d (%.2f%%)", mult, pos, tot, 100.0*pos/tot)

    int rc = JOptionPane.showConfirmDialog(null,
            "Satisfied with Drd1 classification?\nYes: continue\nNo: retry\nCancel: exit",
            "Confirm Drd1", JOptionPane.YES_NO_CANCEL_OPTION)

    if (rc == JOptionPane.YES_OPTION) break
    if (rc == JOptionPane.NO_OPTION) continue
    return
}

// =====================================================
// PART 3: CCKBR CLASSIFICATION (stores Cckbr_pos measurement)
// =====================================================
while (true) {
    double mult = askMultiplier("Cckbr")
    if (mult == null) return
    double thr = otsuCckbr * mult
    println "INFO: Effective Cckbr threshold: ${thr}"

    getDetectionObjects().each { cell ->
        double mean = cell.getMeasurementList().getMeasurementValue("Nucleus: Cckbr mean")
        boolean isPos = (mean >= thr)
        setBinaryMeasurement(cell, "Cckbr_pos", isPos)
        cell.setPathClass(isPos ? getPathClass("Cckbr+") : getPathClass("Cckbr-"))
    }

    fireHierarchyUpdate()
    getCurrentViewer().repaint()

    int tot = totalCells()
    int pos = countPos("Cckbr_pos")
    println String.format("INFO: >>> Cckbr+ at multiplier %.3f: %d/%d (%.2f%%)", mult, pos, tot, 100.0*pos/tot)

    int rc = JOptionPane.showConfirmDialog(null,
            "Satisfied with Cckbr classification?\nYes: continue\nNo: retry\nCancel: exit",
            "Confirm Cckbr", JOptionPane.YES_NO_CANCEL_OPTION)

    if (rc == JOptionPane.YES_OPTION) break
    if (rc == JOptionPane.NO_OPTION) continue
    return
}

// =====================================================
// PART 4: A2A CLASSIFICATION (stores A2a_pos measurement)
// =====================================================
while (true) {
    double mult = askMultiplier("A2a")
    if (mult == null) return
    double thr = otsuA2a * mult
    println "INFO: Effective A2a threshold: ${thr}"

    getDetectionObjects().each { cell ->
        double mean = cell.getMeasurementList().getMeasurementValue("Nucleus: A2a mean")
        boolean isPos = (mean >= thr)
        setBinaryMeasurement(cell, "A2a_pos", isPos)
        cell.setPathClass(isPos ? getPathClass("A2a+") : getPathClass("A2a-"))
    }

    fireHierarchyUpdate()
    getCurrentViewer().repaint()

    int tot = totalCells()
    int pos = countPos("A2a_pos")
    println String.format("INFO: >>> A2a+ at multiplier %.3f: %d/%d (%.2f%%)", mult, pos, tot, 100.0*pos/tot)

    int rc = JOptionPane.showConfirmDialog(null,
            "Satisfied with A2a classification?\nYes: finish & summarize\nNo: retry\nCancel: exit",
            "Confirm A2a", JOptionPane.YES_NO_CANCEL_OPTION)

    if (rc == JOptionPane.YES_OPTION) break
    if (rc == JOptionPane.NO_OPTION) continue
    return
}

// =====================================================
// FINAL: Print single-marker + pairwise + triple counts
// =====================================================
int tot = totalCells()
int d1  = countPos("Drd1_pos")
int ck  = countPos("Cckbr_pos")
int a2  = countPos("A2a_pos")

def d1ck = getDetectionObjects().count {
    it.getMeasurementList().getMeasurementValue("Drd1_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("Cckbr_pos") == 1.0
}
def d1a2 = getDetectionObjects().count {
    it.getMeasurementList().getMeasurementValue("Drd1_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("A2a_pos") == 1.0
}
def cka2 = getDetectionObjects().count {
    it.getMeasurementList().getMeasurementValue("Cckbr_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("A2a_pos") == 1.0
}
def triple = getDetectionObjects().count {
    it.getMeasurementList().getMeasurementValue("Drd1_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("Cckbr_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("A2a_pos") == 1.0
}

println "INFO: =============================="
println String.format("INFO: TOTAL cells: %d", tot)
println String.format("INFO: Drd1+  : %d (%.2f%%)", d1, 100.0*d1/tot)
println String.format("INFO: Cckbr+ : %d (%.2f%%)", ck, 100.0*ck/tot)
println String.format("INFO: A2a+   : %d (%.2f%%)", a2, 100.0*a2/tot)
println "INFO: --- Pairwise positives ---"
println String.format("INFO: Drd1+ & Cckbr+ : %d (%.2f%%)", d1ck, 100.0*d1ck/tot)
println String.format("INFO: Drd1+ & A2a+   : %d (%.2f%%)", d1a2, 100.0*d1a2/tot)
println String.format("INFO: Cckbr+ & A2a+  : %d (%.2f%%)", cka2, 100.0*cka2/tot)
println "INFO: --- Triple positive ---"
println String.format("INFO: Drd1+ & Cckbr+ & A2a+ : %d (%.2f%%)", triple, 100.0*triple/tot)
println "INFO: =============================="
// =====================================================
// OPTIONAL: Assign a combined class so QuPath can count groups normally
// This will overwrite the temporary A2a+/A2a- coloring.
// =====================================================
getDetectionObjects().each { cell ->
    boolean d = cell.getMeasurementList().getMeasurementValue("Drd1_pos") == 1.0
    boolean c = cell.getMeasurementList().getMeasurementValue("Cckbr_pos") == 1.0
    boolean a = cell.getMeasurementList().getMeasurementValue("A2a_pos") == 1.0

    String cname = "Drd1" + (d ? "+" : "-") + ":Cckbr" + (c ? "+" : "-") + ":A2a" + (a ? "+" : "-")
    cell.setPathClass(getPathClass(cname))
}

fireHierarchyUpdate()
getCurrentViewer().repaint()
println "INFO: Assigned combined classes Drd1:Cckbr:A2a for all detections."
// --- EXPORT + 9-way summary ---
def dets = getDetectionObjects()
int total_cells = dets.size()

int c1 = dets.count {
    it.getMeasurementList().getMeasurementValue("Drd1_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("Cckbr_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("A2a_pos") == 1.0
}
int c2 = dets.count {
    it.getMeasurementList().getMeasurementValue("Drd1_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("Cckbr_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("A2a_pos") == 0.0
}
int c3 = dets.count {
    it.getMeasurementList().getMeasurementValue("Drd1_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("Cckbr_pos") == 0.0 &&
    it.getMeasurementList().getMeasurementValue("A2a_pos") == 1.0
}
int c4 = dets.count {
    it.getMeasurementList().getMeasurementValue("Drd1_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("Cckbr_pos") == 0.0 &&
    it.getMeasurementList().getMeasurementValue("A2a_pos") == 0.0
}
int c5 = dets.count {
    it.getMeasurementList().getMeasurementValue("Drd1_pos") == 0.0 &&
    it.getMeasurementList().getMeasurementValue("Cckbr_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("A2a_pos") == 1.0
}
int c6 = dets.count {
    it.getMeasurementList().getMeasurementValue("Drd1_pos") == 0.0 &&
    it.getMeasurementList().getMeasurementValue("Cckbr_pos") == 1.0 &&
    it.getMeasurementList().getMeasurementValue("A2a_pos") == 0.0
}
int c7 = dets.count {
    it.getMeasurementList().getMeasurementValue("Drd1_pos") == 0.0 &&
    it.getMeasurementList().getMeasurementValue("Cckbr_pos") == 0.0 &&
    it.getMeasurementList().getMeasurementValue("A2a_pos") == 1.0
}
int c8 = dets.count {
    it.getMeasurementList().getMeasurementValue("Drd1_pos") == 0.0 &&
    it.getMeasurementList().getMeasurementValue("Cckbr_pos") == 0.0 &&
    it.getMeasurementList().getMeasurementValue("A2a_pos") == 0.0
}

int sum_of_8 = c1 + c2 + c3 + c4 + c5 + c6 + c7 + c8

println String.format("SUMMARY: total_cells: %d", total_cells)
println String.format("SUMMARY: Drd1+:Cckbr+:A2a+ : %d", c1)
println String.format("SUMMARY: Drd1+:Cckbr+:A2a- : %d", c2)
println String.format("SUMMARY: Drd1+:Cckbr-:A2a+ : %d", c3)
println String.format("SUMMARY: Drd1+:Cckbr-:A2a- : %d", c4)
println String.format("SUMMARY: Drd1-:Cckbr+:A2a+ : %d", c5)
println String.format("SUMMARY: Drd1-:Cckbr+:A2a- : %d", c6)
println String.format("SUMMARY: Drd1-:Cckbr-:A2a+ : %d", c7)
println String.format("SUMMARY: Drd1-:Cckbr-:A2a- : %d", c8)

if (sum_of_8 != total_cells) {
    println String.format("WARNING: Sum of 8 combinations (%d) != total_cells (%d)", sum_of_8, total_cells)
}

// Write 1-row summary CSV to project directory
def baseDir = getProject().getBaseDirectory()
def imageName = getProjectEntry().getImageName()
def summaryFile = new File(baseDir, imageName + "_summary.csv")
def header = "image,total_cells,Drd1p_Cckbrp_A2ap,Drd1p_Cckbrp_A2an,Drd1p_Cckbrn_A2ap,Drd1p_Cckbrn_A2an,Drd1n_Cckbrp_A2ap,Drd1n_Cckbrp_A2an,Drd1n_Cckbrn_A2ap,Drd1n_Cckbrn_A2an"
def row = String.format("%s,%d,%d,%d,%d,%d,%d,%d,%d,%d", imageName, total_cells, c1, c2, c3, c4, c5, c6, c7, c8)
summaryFile.withWriter('UTF-8') { writer ->
    writer.write(header)
    writer.newLine()
    writer.write(row)
    writer.newLine()
}
println "INFO: Wrote summary CSV to " + summaryFile.getAbsolutePath()

// Export per-cell measurements table
def cellsFile = new File(baseDir, imageName + "_cells.csv")
QPEx.exportObjectsToCSV(cellsFile.getAbsolutePath(), getDetectionObjects())
println "INFO: Wrote per-cell CSV to " + cellsFile.getAbsolutePath()

