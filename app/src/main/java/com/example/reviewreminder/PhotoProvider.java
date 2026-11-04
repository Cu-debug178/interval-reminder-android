package com.example.reviewreminder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class PhotoProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    static Uri uri(File photo){return new Uri.Builder().scheme("content").authority("com.example.reviewreminder.photos").appendPath(photo.getName()).build();}
    private File file(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();if(name==null||!name.matches("capture-[a-zA-Z0-9-]+\\.jpg"))throw new FileNotFoundException("Invalid photo");
        return new File(new File(getContext().getFilesDir(),"photos"),name);
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode));}
    public String getType(Uri uri){return "image/jpeg";}
    public Cursor query(Uri uri,String[] projection,String s,String[] args,String sort){
        try{File f=file(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(cols);Object[] values=new Object[cols.length];for(int i=0;i<cols.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(cols[i])?f.getName():OpenableColumns.SIZE.equals(cols[i])?f.length():null;c.addRow(values);return c;}catch(Exception e){return null;}
    }
    public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    public int delete(Uri uri,String s,String[] args){throw new UnsupportedOperationException();}
    public int update(Uri uri,ContentValues v,String s,String[] args){throw new UnsupportedOperationException();}
}
