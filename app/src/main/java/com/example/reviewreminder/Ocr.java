package com.example.reviewreminder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.*;

final class Ocr {
    static Bitmap decode(File f) throws IOException {
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getAbsolutePath(),o);
        if(o.outWidth<1)throw new IOException("无法读取这张照片");
        o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>2400)o.inSampleSize*=2;
        o.inJustDecodeBounds=false;o.inPreferredConfig=Bitmap.Config.ARGB_8888;Bitmap b=BitmapFactory.decodeFile(f.getAbsolutePath(),o);if(b==null)throw new IOException("照片解码失败");
        int orientation=new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);Matrix m=new Matrix();
        switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;}
        if(!m.isIdentity()){Bitmap rotated=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);if(rotated!=b)b.recycle();b=rotated;}return b;
    }
    static synchronized String recognize(Context c,Bitmap image) throws Exception {
        File root=new File(c.getFilesDir(),"ocr"),data=new File(root,"tessdata");if(!data.isDirectory()&&!data.mkdirs())throw new IOException("无法初始化识别模型");
        for(String lang:new String[]{"eng","chi_sim"}){File to=new File(data,lang+".traineddata");if(!to.exists()){File temp=new File(data,lang+".tmp");try(InputStream in=c.getAssets().open("tessdata/"+lang+".traineddata");FileOutputStream out=new FileOutputStream(temp)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();}if(!temp.renameTo(to))throw new IOException("无法保存识别模型");}}
        TessBaseAPI api=new TessBaseAPI();try{if(!api.init(root.getAbsolutePath(),"eng+chi_sim",TessBaseAPI.OEM_LSTM_ONLY))throw new IOException("识别模型初始化失败");api.setPageSegMode(6);api.setImage(image);return api.getUTF8Text();}finally{api.recycle();}
    }
}
