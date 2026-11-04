package com.example.reviewreminder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class ReviewSession {
    private final List<Integer> queue=new ArrayList<>();private final int[] attempts;private int position;private int remembered;private int repeats;
    public ReviewSession(int size,boolean shuffle,long seed){attempts=new int[size];for(int i=0;i<size;i++)queue.add(i);if(shuffle)Collections.shuffle(queue,new Random(seed));}
    public boolean ended(){return position>=queue.size();}
    public int current(){if(ended())throw new IllegalStateException("Session finished");return queue.get(position);}
    public int answered(){return position;}
    public int total(){return queue.size();}
    public int remembered(){return remembered;}
    public int repeats(){return repeats;}
    public void answer(boolean know){int card=current();attempts[card]++;if(know)remembered++;else{repeats++;if(attempts[card]<3)queue.add(Math.min(queue.size(),position+4),card);}position++;}
}
