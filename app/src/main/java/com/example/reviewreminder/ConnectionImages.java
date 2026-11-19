package com.example.reviewreminder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Private, app-relative connection images with path traversal checks. */
final class ConnectionImages {
    private static final String DIR="connection-images";
    private ConnectionImages(){}
    private static File dir(Context c){File d=new File(c.getFilesDir(),DIR);if(!d.exists()&&!d.mkdirs())throw new IllegalStateException("无法创建连接图目录");return d;}

    static File file(Context c,String relative){
        if(relative==null||relative.trim().isEmpty())return null;
        String name=relative.trim();if(name.contains("/")||name.contains("\\")||name.startsWith("."))return null;
        File base=dir(c),target=new File(base,name);return name.equals(target.getName())?target:null;
    }
    static boolean exists(Context c,String relative){File f=file(c,relative);return f!=null&&f.isFile()&&f.length()>0;}
    static Bitmap decode(Context c,String relative,int maxSide)throws IOException{
        File f=file(c,relative);if(f==null||!f.isFile())throw new IOException("连接图已丢失");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getAbsolutePath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("连接图无法读取");
        int sample=1,side=Math.max(bounds.outWidth,bounds.outHeight);while(maxSide>0&&side/sample>maxSide)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,sample);
        Bitmap bitmap=BitmapFactory.decodeFile(f.getAbsolutePath(),options);if(bitmap==null)throw new IOException("连接图无法解码");return bitmap;
    }
    static String importUri(Context c,Uri uri)throws IOException{
        if(uri==null)throw new IOException("未选择图片");
        InputStream first=c.getContentResolver().openInputStream(uri);if(first==null)throw new IOException("无法读取图片");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try{BitmapFactory.decodeStream(first,null,bounds);}finally{first.close();}
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("所选文件不是有效图片");
        int sample=1,side=Math.max(bounds.outWidth,bounds.outHeight);while(side/sample>1600)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,sample);
        Bitmap bitmap;try(InputStream second=c.getContentResolver().openInputStream(uri)){if(second==null)throw new IOException("无法读取图片");bitmap=BitmapFactory.decodeStream(second,null,options);}
        if(bitmap==null)throw new IOException("图片无法解码");
        try{return saveBitmap(c,bitmap);}finally{bitmap.recycle();}
    }
    static String saveBitmap(Context c,Bitmap bitmap)throws IOException{
        if(bitmap==null||bitmap.getWidth()<1||bitmap.getHeight()<1)throw new IOException("图片为空");
        String name="ci_"+UUID.randomUUID()+".jpg";File target=new File(dir(c),name),tmp=new File(target.getParentFile(),name+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,84,out))throw new IOException("图片压缩失败");
            out.getFD().sync();
        }
        if(!tmp.renameTo(target)){tmp.delete();throw new IOException("连接图保存失败");}
        return name;
    }
    static void delete(Context c,String relative){File f=file(c,relative);if(f!=null)f.delete();}
    static String base64(Context c,String relative)throws IOException{
        File f=file(c,relative);if(f==null||!f.isFile())throw new IOException("连接图已丢失");
        if(f.length()>8L*1024*1024)throw new IOException("连接图过大，无法备份");
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(f)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
        return Base64Compat.encode(out.toByteArray());
    }
    static final class StagedFile {
        final File temp,target;
        StagedFile(File temp,File target){this.temp=temp;this.target=target;}
    }
    /** Write beside the destination so a failed restore can discard the temp without replacing the current image. */
    static StagedFile stage(Context c,String relative,String encoded)throws IOException{
        File target=file(c,relative);if(target==null)throw new IOException("连接图路径无效");
        byte[] bytes=Base64Compat.decode(encoded);if(bytes.length==0)throw new IOException("连接图为空");
        File tmp=new File(target.getParentFile(),target.getName()+".part");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(bytes);out.getFD().sync();}
        return new StagedFile(tmp,target);
    }
    static void restore(Context c,String relative,String encoded)throws IOException{
        StagedFile staged=stage(c,relative,encoded);
        if(!publish(staged.temp,staged.target)){staged.temp.delete();throw new IOException("连接图恢复失败");}
    }
    static boolean publish(File temp,File target){
        if(temp.renameTo(target))return true;
        return target.exists()&&target.delete()&&temp.renameTo(target);
    }
}
