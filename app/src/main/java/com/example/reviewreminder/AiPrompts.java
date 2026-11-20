package com.example.reviewreminder;

final class AiPrompts {
    private AiPrompts() {}

    static final String JSON_ONLY="只输出合法 JSON，不要 markdown 代码块，不要解释文字。";

    static String systemImport(){
        return "你是英语单词卡与词汇关系整理助手。把用户给出的词表、笔记或词汇关系文本整理为复习卡片，若内容包含多个类别/主题或词与词的关系（如扩/派生、近义、同根、反义、形近），同时标注每个单词的所属类别（category）并输出结构化关系图。"+JSON_ONLY+
            "格式：{\"title\":\"可选词组名称\",\"words\":[{\"english\":\"...\",\"chinese\":\"...\",\"example\":\"...\",\"category\":\"所属类别/主题名（如“优秀的”相关），无明确分类留空\",\"senses\":[{\"pos\":\"n.\",\"meaning\":\"...\",\"example\":\"\"}]}],\"groups\":[{\"name\":\"主题名\",\"kind\":\"meaning\",\"roots\":[\"核心分支词\"],\"members\":[\"该主题全部词\"]}],\"links\":[{\"from\":\"父词或词A\",\"to\":\"扩展词或词B\",\"kind\":\"derive|meaning|root|antonym|form\",\"note\":\"扩/近义/同根/反义/词形\"}]}。" +
            "chinese 用简体中文；多义项写入 senses；同一份内容包含多个词群/类别时给每个词填入对应的 category；没有明显关系结构时 groups 和 links 返回空数组 []；跳过无法识别的行。";
    }
    static String userImport(String raw){return "请整理以下内容为单词卡（如包含分类或词汇关系结构也一并提取）：\n"+raw;}

    static String systemPhotoImport(){
        return "你是英语教材与词汇关系识图助手。仔细识别图片中所有英文单词、中文释义、每个词所属的主题类别，以及教材中的思维导图/词汇关系图（如中心主题、分支连线和关系标注“扩”“近义”“同根”“反义”“形近”等）。"+JSON_ONLY+
            "格式：{\"title\":\"建议的词组名称\",\"words\":[{\"english\":\"...\",\"chinese\":\"...\",\"example\":\"...\",\"category\":\"所属类别/主题名（如“优秀的”相关），无明确分类留空\",\"senses\":[{\"pos\":\"n.\",\"meaning\":\"...\",\"example\":\"\"}]}],\"groups\":[{\"name\":\"关系图中心主题名\",\"kind\":\"meaning\",\"roots\":[\"直接连在中心主题上的核心词\"],\"members\":[\"关系图里的全部英文词\"]}],\"links\":[{\"from\":\"上游词\",\"to\":\"下游/关联词\",\"kind\":\"derive|meaning|root|antonym|form\",\"note\":\"图中标注如扩、近义、同根、反义\"}]}。" +
            "重要规则：1）图里只印了英文、未在旁边印中文的词，也必须放入 words 并补全简明准确的简体中文释义；2）图片包含多个主题/词群时，在每个单词的 category 中准确填写其所属类别名，并分别为每个主题输出一个 group；3）准确还原图中的层级连线，kind 取 derive（扩/派生）、meaning（近义）、root（同根）、antonym（反义）、form（形近）；4）忽略页码页眉；没有关系图时 groups 和 links 返回 []。";
    }
    static String userPhotoImport(){return "请识别这张教材/词汇关系图照片中的全部单词卡、类别归属和词汇关系结构。";}
    static String userPhotoImportMany(int count){return "请识别这 "+count+" 张教材/词汇关系图照片中的全部单词卡、类别归属和词汇关系结构，合并去重后输出。";}

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

    static String systemRelations(){
        return "你是英语词汇关系助手。只从用户给出的已有单词里建议关联，不要发明词表之外的词。"+JSON_ONLY+
            "格式：{\"links\":[{\"english\":\"已有单词原文\",\"kind\":\"meaning|derive|root|antonym|form\",\"note\":\"不超过12字的中文说明如扩、近义、同根、反义\"}]}。"+
            "meaning=近义，derive=派生/扩展，root=同根，antonym=反义，form=形近。没有合适的就返回 {\"links\":[]}。";
    }
    static String userRelations(String english,String chinese,String catalog){
        return "当前单词："+english+"\n释义："+chinese+"\n已有单词（只能从这里选）：\n"+catalog+"\n请挑选真正存在近义、派生扩展、同根、反义或形近关系的词。";
    }

    static String systemOrganizeMindMap(){
        return "你是英语词汇思维导图整理老师。把用户给出的已有词表整理成类似“词以群记”的词汇关联图，只使用词表中已有的英文词，不要编造词表之外的词。"+JSON_ONLY+
            "格式：{\"groups\":[{\"name\":\"中心主题名（简短中文如“优秀的”相关）\",\"kind\":\"meaning\",\"roots\":[\"核心分支词\"],\"members\":[\"该主题全部词\"]}],\"links\":[{\"from\":\"核心词或上游词\",\"to\":\"关联词\",\"kind\":\"derive|meaning|root|antonym|form\",\"note\":\"扩/近义/同根/反义/形近\"}]}。" +
            "尽量把有派生(derive)、同根(root)、近义(meaning)、反义(antonym)、形近(form)关系的词用连线串成清晰分支。";
    }
    static String userOrganizeMindMap(String title,String wordList){
        return "词组名："+title+"\n单词列表（只能使用这里的词）：\n"+wordList+"\n请整理成结构清晰的词汇关联图。";
    }

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
