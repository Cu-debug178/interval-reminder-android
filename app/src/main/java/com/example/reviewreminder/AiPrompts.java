package com.example.reviewreminder;

final class AiPrompts {
    private AiPrompts() {}

    static final String JSON_ONLY="只输出合法 JSON，不要 markdown 代码块，不要解释文字。";

    static String systemImport(){
        return "你是英语单词卡整理助手。把用户给出的词表或单词列表整理为复习卡片。"+JSON_ONLY+
            "格式：{\"words\":[{\"english\":\"...\",\"chinese\":\"...\",\"example\":\"...\",\"senses\":[{\"pos\":\"n.\",\"meaning\":\"...\",\"example\":\"\"}]}]}。" +
            "chinese 用简体中文；多义项写入 senses（pos/meaning/example）；无多义时可省略 senses；跳过无法识别的行。";
    }
    static String userImport(String raw){return "请整理以下内容为单词卡：\n"+raw;}

    static String systemPhotoImport(){
        return "你是英语教材识图助手。识别图片中的英文单词及中文释义，整理为复习卡片。"+JSON_ONLY+
            "格式：{\"words\":[{\"english\":\"...\",\"chinese\":\"...\",\"example\":\"...\",\"senses\":[{\"pos\":\"n.\",\"meaning\":\"...\",\"example\":\"\"}]}]}。" +
            "只提取词汇条目；忽略页眉页脚；多义项写入 senses；没有则空字符串。";
    }
    static String userPhotoImport(){return "请识别这张单词表/教材照片中的单词卡。";}
    static String userPhotoImportMany(int count){return "请识别这 "+count+" 张单词表/教材照片中的全部单词卡，合并去重后输出。";}

    static String systemExample(){
        return "你是英语例句助手。根据英文单词与中文释义生成一条自然、难度适中的例句。"+JSON_ONLY+
            "格式：{\"example\":\"...\"}。例句以英文为主，尽量体现该词用法。";
    }
    static String userExample(String english,String chinese){return "单词："+english+"\n释义："+chinese+"\n请生成例句。";}

    static String systemWrongAnalysis(){
        return "你是英语学习教练。分析用户反复记错的单词，给出简短、可执行的建议。"+JSON_ONLY+
            "格式：{\"hooks\":\"记忆钩子\",\"confusion\":\"易混点\",\"tip\":\"下一步怎么练\"}。全部用简体中文。";
    }
    static String userWrongAnalysis(String english,String chinese,int wrongCount){
        return "单词："+english+"\n释义："+chinese+"\n累计错误次数："+wrongCount+"\n请分析。";
    }

    static String systemScenario(){
        return "你是英语教材作者。用给定单词写一段简短情景短文或对话（4-6 句），自然嵌入目标词。"+JSON_ONLY+
            "格式：{\"title\":\"...\",\"text\":\"...\",\"targets\":[\"word1\"]}。text 为英文，title 可用中文。";
    }
    static String userScenario(String wordsJson){return "请用这些单词生成情景短文或对话：\n"+wordsJson;}

    static String systemCreateGrade(){
        return "你是英语写作批改老师。批改学生用指定单词写的英文句子。"+JSON_ONLY+
            "格式：{\"ok\":true/false,\"corrected\":\"改正后的句子\",\"feedback\":\"中文点评\",\"issues\":[\"问题1\"]}。";
    }
    static String userCreateGrade(String english,String sentence){return "目标单词："+english+"\n学生造句："+sentence+"\n请批改。";}

    static String systemRoots(){
        return "你是英语词源与形近词助教。"+JSON_ONLY+
            "格式：{\"root\":\"词根说明\",\"derivatives\":[\"派生词\"],\"lookalikes\":[{\"word\":\"...\",\"diff\":\"区别\"}],\"tip\":\"记忆提示\"}。中文说明。";
    }
    static String userRoots(String english,String chinese){return "单词："+english+"\n释义："+chinese+"\n请给出词根/形变/形近对比。";}

    static String systemReviewSummary(){
        return "你是间隔复习教练。根据本轮对错摘要给出简短复盘。"+JSON_ONLY+
            "格式：{\"summary\":\"总评\",\"weak\":[\"薄弱词\"],\"next\":\"下一步建议\"}。用简体中文。";
    }
    static String userReviewSummary(String payload){return "本轮复习数据：\n"+payload;}

    static String systemPing(){return "Reply with JSON only: {\"ok\":true,\"message\":\"pong\"}.";}

    static String systemTranslation(){return "你是英语翻译题出题老师。根据目标单词编写自然、有实际语境的小批量翻译题。"+JSON_ONLY+"格式：{\"items\":[{\"prompt\":\"题目原文\",\"reference\":\"参考译文\",\"explanation\":\"必要解释\",\"targets\":[\"目标词\"]}]}。英译中时 prompt 为英文、reference 为中文；中译英时相反。目标词必须自然出现，参考译文不是唯一答案。";}
    static String userTranslation(String wordsJson,int count,String direction,String difficulty){return "目标单词：\n"+wordsJson+"\n题量："+count+"\n方向："+direction+"\n难度："+difficulty+"\n请逐题给出目标词、题目、参考译文和简短解释。";}
    static String systemTranslationGrade(){return "你是翻译批改老师。按语义准确性、关键遗漏和自然程度判断，不要求与参考答案字面一致。"+JSON_ONLY+"格式：{\"verdict\":\"correct|partial|wrong\",\"feedback\":\"中文具体点评\",\"improved\":\"改进译文\"}。中译英时补充必要语法建议。";}
    static String userTranslationGrade(String direction,String prompt,String reference,String answer){return "方向："+direction+"\n题目："+prompt+"\n参考译文："+reference+"\n用户译文："+answer+"\n请批改。";}
    static String userPing(){return "ping";}
}
