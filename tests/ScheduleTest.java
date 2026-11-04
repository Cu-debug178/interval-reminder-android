import com.example.reviewreminder.Schedule;
import java.util.*;
public class ScheduleTest {
  static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
  static long at(int d,int h,int m){Calendar c=Calendar.getInstance();c.clear();c.set(2026,Calendar.NOVEMBER,d,h,m);return c.getTimeInMillis();}
  public static void main(String[] a){
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    List<int[]> none=Collections.emptyList();
    check(Schedule.due(at(3,10,0),5,none)==at(3,10,5),"5 minutes");
    check(Schedule.due(at(3,10,30),720,none)==at(3,22,30),"12 hours");
    List<int[]> day=Arrays.asList(new int[]{450,510},new int[]{1200,1320});
    check(Schedule.nextWindow(at(3,8,0),day)==at(3,8,0),"inside");
    check(Schedule.nextWindow(at(3,13,0),day)==at(3,20,0),"next window");
    check(Schedule.nextWindow(at(3,23,0),day)==at(4,7,30),"next day");
    List<int[]> night=Arrays.asList(new int[]{1320,120});
    check(Schedule.nextWindow(at(4,1,0),night)==at(4,1,0),"overnight");
    check(Schedule.nextWindow(at(4,2,0),night)==at(4,22,0),"end exclusive");
    check(Schedule.due(at(5,10,0),1440,none)==at(6,10,0),"completion based");
    System.out.println("ScheduleTest: 8 checks passed");
  }
}
