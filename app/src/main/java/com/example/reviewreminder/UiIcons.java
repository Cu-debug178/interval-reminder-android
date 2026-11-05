package com.example.reviewreminder;

import android.graphics.drawable.Drawable;
import android.widget.Button;

final class UiIcons {
    private UiIcons() {}
    static void apply(Button button,int color){
        String text=button.getText().toString();int icon=0;
        if(text.contains("朗读"))icon=R.drawable.ic_volume_2;
        else if(text.contains("拍摄"))icon=R.drawable.ic_camera;
        else if(text.contains("相册")||text.contains("照片录词")||text.contains("原照片"))icon=R.drawable.ic_image_plus;
        else if(text.contains("上一张")||text.contains("返回"))icon=R.drawable.ic_arrow_left;
        else if(text.contains("下一张"))icon=R.drawable.ic_arrow_right;
        else if(text.contains("删除")||text.equals("−"))icon=R.drawable.ic_trash_2;
        else if(text.contains("列表"))icon=R.drawable.ic_list;
        else if(text.contains("旋转")||text.contains("再看")||text.contains("再复习"))icon=R.drawable.ic_rotate_cw;
        else if(text.contains("编辑")||text.contains("校对"))icon=R.drawable.ic_pencil;
        else if(text.contains("保存")||text.equals("记得")||text.contains("完成本轮"))icon=R.drawable.ic_check;
        else if(text.contains("添加")||text.contains("新建"))icon=R.drawable.ic_plus;
        else if(text.contains("复习")||text.contains("Lesson")||text.contains("显示答案"))icon=R.drawable.ic_book_open;
        else if(text.contains("设置"))icon=R.drawable.ic_settings_2;
        if(icon==0)return;
        Drawable drawable=button.getContext().getDrawable(icon).mutate();drawable.setTint(color);int size=(int)(18*button.getResources().getDisplayMetrics().density+.5f);drawable.setBounds(0,0,size,size);button.setCompoundDrawables(drawable,null,null,null);button.setCompoundDrawablePadding(size/3);
    }
}
