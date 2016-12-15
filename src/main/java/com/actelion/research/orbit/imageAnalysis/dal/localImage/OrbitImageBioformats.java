/*
 *     Orbit, a versatile image analysis software for biological image-based quantification.
 *     Copyright (C) 2009 - 2016 Actelion Pharmaceuticals Ltd., Gewerbestrasse 16, CH-4123 Allschwil, Switzerland.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.actelion.research.orbit.imageAnalysis.dal.localImage;

import com.actelion.research.orbit.dal.IOrbitImage;
import com.actelion.research.orbit.exceptions.OrbitImageServletException;
import com.actelion.research.orbit.imageAnalysis.utils.OrbitUtils;
import edu.emory.mathcs.backport.java.util.Collections;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.gui.BufferedImageReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.media.jai.PlanarImage;
import java.awt.*;
import java.awt.image.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class OrbitImageBioformats implements IOrbitImage {

    private static final Logger logger = LoggerFactory.getLogger(OrbitImageBioformats.class);
    final int maxThumbWidth = 300;
    final protected ThreadLocal<BufferedImageReader> reader;
    final List<BufferedImageReader> allReaders = Collections.synchronizedList(new ArrayList<BufferedImageReader>());
    private String filename;
    private int optimalTileWidth;
    private int optimalTileHeight;
    private int numLevels;
    private int level;
    private boolean interleaved;
    private ColorModel colorModel;
    private SampleModel sampleModel;
    private int numBands;
    private int numBandsOriginal;
    private int originalBitsPerSample;
    private boolean originalWasGrayScale;
    private int tileGridXOffset;
    private int tileGridYOffset;
    private int minX;
    private int minY;
    private long width;
    private long height;


    public OrbitImageBioformats(final String filename, final int level) throws IOException, FormatException {
        this.filename = filename+"["+level+"]";    // level here is important because filename is part of key for hashing!
        this.level = level;

        logger.info("bioformats image: "+this.filename);

        reader = new ThreadLocal<BufferedImageReader>() {
            @Override
            protected BufferedImageReader initialValue() {
                try {
                    logger.debug("init bioformats: "+filename+" ["+level+"]");
                    ImageReader r = new ImageReader();
                    //r.setAllowOpenFiles(false);
                    r.setFlattenedResolutions(false);
                    r.setId(filename);
                    int series = 0;
                    if (!filename.toLowerCase().endsWith("vsi")) {
                        String[] file2series = r.getSeriesUsedFiles();
                        if (file2series != null && file2series.length > 0) {
                            for (int i = 0; i < file2series.length; i++) {
                                String fn = file2series[i];
                                if (fn.equals(r.getCurrentFile())) {
                                    series = i;
                                    logger.debug("selected series: " + series);
                                }
                            }
                            r.setSeries(series);
                        }
                    }
                    r.setResolution(level);
                    BufferedImageReader bir = BufferedImageReader.makeBufferedImageReader(r);
                    synchronized (allReaders) {
                        allReaders.add(bir); // remember for closing
                    }
                    return bir;
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }
        };

        numLevels =  reader.get().getResolutionCount(); // TODO get real resolutions
        //numLevels = getRealResolutionCount(reader.get());

        logger.debug("actual level: "+this.level+" / numLevels: "+numLevels);
        boolean levelOk;
        do {
            levelOk = true;
            if (this.level >= numLevels) {
                close();
                throw new OrbitImageServletException("image pyramid level " + this.level + " does not exist for image " + filename);
            }

            width = reader.get().getSizeX();
            height = reader.get().getSizeY();
            numBandsOriginal = reader.get().getSizeC();

//            int optimalTileWidth2 = OrbitUtils.TILE_SIZE_DEFAULT;
//            int optimalTileHeight2 = OrbitUtils.TILE_SIZE_DEFAULT;
//            String ending = RawUtilsCommon.getExtension(filename, true);
//            if (ending.equals("png") || ending.equals("jpg") || ending.equals("jpeg") || ending.equals("gif") || ending.equals("bmp")
//                    || ((width * height) <= (2048L * 2048L))) {
//                optimalTileWidth2 = (int) width;
//                optimalTileHeight2 = (int) height;
//            }
//            optimalTileWidth = optimalTileWidth2;
//            optimalTileHeight = optimalTileHeight2;


            BufferedImageReader bir = reader.get();
            if (bir.getResolution()!=this.level) bir.setResolution(this.level);
            optimalTileWidth = bir.getOptimalTileWidth();
            optimalTileHeight = bir.getOptimalTileHeight();
            if (optimalTileWidth< OrbitUtils.TILE_SIZE_DEFAULT && width>=OrbitUtils.TILE_SIZE_DEFAULT) optimalTileWidth = OrbitUtils.TILE_SIZE_DEFAULT;
            if (optimalTileHeight< OrbitUtils.TILE_SIZE_DEFAULT && height>=OrbitUtils.TILE_SIZE_DEFAULT) optimalTileHeight = OrbitUtils.TILE_SIZE_DEFAULT;


            logger.debug("optimal tile size: "+optimalTileWidth+" x "+optimalTileHeight);


            originalBitsPerSample = bir.getBitsPerPixel();
            interleaved = bir.isInterleaved();
            try {
                BufferedImage img = getPlane(0, 0);
                colorModel = img.getColorModel();
                sampleModel = img.getSampleModel();
                numBands = sampleModel.getNumBands();
                originalWasGrayScale = numBands==1;
                tileGridXOffset = img.getTileGridXOffset();
                tileGridYOffset = img.getTileGridYOffset();
                minX = img.getMinX();
                minY = img.getMinY();
            } catch (Exception e) {
                if (this.level<numLevels-1) {
                    this.level++;
                    synchronized (allReaders) {
                        for (IFormatReader r : allReaders) {
                            r.setResolution(this.level);
                        }
                    }
                    levelOk=false;
                    logger.debug("error loading level "+this.level+" trying next level");
                }  else {
                    e.printStackTrace();
                    throw new OrbitImageServletException("error loading level " + this.level + " (and not more alternative levels available). File: "+filename);
                }
            }

        } while (!levelOk);

        logger.info(filename+" loaded ["+width+" x "+height+"]");
    }

    @Deprecated
    private int getRealResolutionCount(IFormatReader imageReader) throws IOException, FormatException {
        ImageReader ir = new ImageReader();
        ir.setFlattenedResolutions(false);
        ir.setId(imageReader.getCurrentFile());
        ir.setSeries(imageReader.getSeries());
        int numRes = 1;
        for (int lev=imageReader.getResolutionCount()-1; lev>=0; lev--) {
            numRes = lev;
            ir.setResolution(lev);
            int thumbW = ir.getSizeX();
            int thumbH = ir.getSizeY();
            double diff = Math.abs((thumbW/(double)thumbH) - (imageReader.getSizeX()/(double)imageReader.getSizeY()));
            System.out.println("diff: "+diff);
            if (diff<0.001) break;
        }
        ir.close();
        return numRes;
    }


    @Override
    public String readInfoString(String filename) throws OrbitImageServletException {
        return "";
    }

    @Override
    public Raster getTileData(int tileX, int tileY) {
        try {
           BufferedImage img = getPlane(tileX, tileY);

           // ensure tiles have always full tileWidth and tileHeight (even at borders)
           if (img.getWidth()!=getTileWidth() || img.getHeight()!=getTileHeight())
           {
               BufferedImage bi = new BufferedImage(getTileWidth(), getTileHeight(), img.getType());
               bi.getGraphics().drawImage(img, 0, 0, null);
               img = bi;
           }

          // set correct bounds
          Raster r = img.getData().createTranslatedChild(PlanarImage.tileXToX(tileX, img.getTileGridXOffset(), getTileWidth()), PlanarImage.tileYToY(tileY, img.getTileGridYOffset(), getTileHeight()));
          return r;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    protected BufferedImage getPlane(int tileX, int tileY) throws Exception {
        int x = optimalTileWidth * tileX;
        int y = optimalTileHeight * tileY;
        int w = (int) Math.min(optimalTileWidth, width - x);
        int h = (int) Math.min(optimalTileHeight, height - y);
        BufferedImageReader bir = reader.get();
        if (bir.getResolution()!=this.level) bir.setResolution(this.level);
        BufferedImage bi = bir.openImage(0, x, y, w, h);
        return bi;
    }

    @Override
    public String getFilename() {
        return filename;
    }

    @Override
    public int getWidth() {
        return (int) width;
    }

    @Override
    public int getHeight() {
        return (int) height;
    }

    @Override
    public int getTileWidth() {
        return optimalTileWidth;
    }

    @Override
    public int getTileHeight() {
        return optimalTileHeight;
    }

    @Override
    public int getTileGridXOffset() {
        return tileGridXOffset;
    }

    @Override
    public int getTileGridYOffset() {
        return tileGridYOffset;
    }

    @Override
    public int getMinX() {
        return minX;
    }

    @Override
    public int getMinY() {
        return minY;
    }

    @Override
    public int getNumBands() {
        return numBands;
    }

    @Override
    public ColorModel getColorModel() {
        return colorModel;
    }

    @Override
    public SampleModel getSampleModel() {
        return sampleModel;
    }

    @Override
    public int getOriginalBitsPerSample() {
        return originalBitsPerSample;
    }

    @Override
    public boolean getOriginalWasGrayScale() {
        return originalWasGrayScale;
    }

    @Override
    public void close() throws IOException {
        synchronized (allReaders) {
            for (IFormatReader r: allReaders) {
                try {
                    r.close();
                } catch (Exception e) {
                }
            }
        }
    }

    public int getNumLevels() {
        return numLevels;
    }

    public int getLevel() {
        return level;
    }


    public BufferedImage getThumbnail() {
         long thumbW=1;
         long thumbH=1;

        try {
            ImageReader ir = new ImageReader();
            ir.setFlattenedResolutions(false);
            ir.setId(reader.get().getCurrentFile());
            ir.setSeries(reader.get().getSeries());

            for (int lev=numLevels-1; lev>=0; lev--) {
                ir.setResolution(lev);
                thumbW = ir.getSizeX();
                thumbH = ir.getSizeY();
                double diff = Math.abs((thumbW/(double)thumbH) - (width/(double)height));
                logger.trace("thumb lev: "+lev+"  diff: "+diff+"  WxH: "+thumbW+"x"+thumbH);
                if (diff<0.001) break;
            }

            BufferedImageReader bir = BufferedImageReader.makeBufferedImageReader(ir);
            BufferedImage thumb = bir.openImage(0);
            bir.close();

            if (thumbW>maxThumbWidth) {
                int h = (int)(maxThumbWidth * (thumbH/(double)thumbW));
                BufferedImage img = new BufferedImage(maxThumbWidth,h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = (Graphics2D) img.getGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(thumb,0,0,maxThumbWidth,h, null);
                thumb = img;
            }
            return thumb;
        } catch (IOException e) {
            e.printStackTrace();
        } catch (loci.formats.FormatException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        final String testImage = "D:\\pic\\testimg.jp2";
        OrbitImageBioformats oi = new OrbitImageBioformats(testImage,0);
        System.out.println("wxh: "+oi.getWidth()+"x"+oi.getHeight());
        BufferedImage bi = new BufferedImage(oi.getColorModel(),  (WritableRaster) oi.getTileData(0,0) , oi.getColorModel().isAlphaPremultiplied(), null);
        System.out.println("img: "+bi);
        oi.close();
    }
}
