package com.example.reviewreminder;

/** Small RFC 4648 codec that works on Android 6 and local JVM tests. */
final class Base64Compat {
    private static final char[] ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private Base64Compat(){}
    static String encode(byte[] data){
        StringBuilder out=new StringBuilder((data.length+2)/3*4);
        for(int i=0;i<data.length;i+=3){int a=data[i]&255,b=i+1<data.length?data[i+1]&255:0,c=i+2<data.length?data[i+2]&255:0;int value=(a<<16)|(b<<8)|c;out.append(ALPHABET[(value>>>18)&63]).append(ALPHABET[(value>>>12)&63]).append(i+1<data.length?ALPHABET[(value>>>6)&63]:'=').append(i+2<data.length?ALPHABET[value&63]:'=');}
        return out.toString();
    }
    static byte[] decode(String text){
        if(text==null)throw new IllegalArgumentException("Base64 为空");String clean=text.replaceAll("\\s","");if(clean.length()%4!=0)throw new IllegalArgumentException("Base64 长度无效");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        for(int i=0;i<clean.length();i+=4){int a=value(clean.charAt(i)),b=value(clean.charAt(i+1));char c=clean.charAt(i+2),d=clean.charAt(i+3);int cv=c=='='?0:value(c),dv=d=='='?0:value(d);int value=(a<<18)|(b<<12)|(cv<<6)|dv;out.write((value>>>16)&255);if(c!='=')out.write((value>>>8)&255);if(d!='='){if(c=='=')throw new IllegalArgumentException("Base64 填充无效");out.write(value&255);}if(i+4<clean.length()&&(c=='='||d=='='))throw new IllegalArgumentException("Base64 填充位置无效");}
        return out.toByteArray();
    }
    private static int value(char c){if(c>='A'&&c<='Z')return c-'A';if(c>='a'&&c<='z')return c-'a'+26;if(c>='0'&&c<='9')return c-'0'+52;if(c=='+')return 62;if(c=='/')return 63;throw new IllegalArgumentException("Base64 字符无效");}
}
