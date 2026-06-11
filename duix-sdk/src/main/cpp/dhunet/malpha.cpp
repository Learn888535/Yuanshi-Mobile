#include "malpha.h"

MWorkMat::MWorkMat(JMat* pic,JMat* msk,const int* boxs,int kind){
    m_boxx = boxs[0];
    m_boxy=boxs[1];
    m_boxwidth=boxs[2]-m_boxx;
    m_boxheight=boxs[3]-m_boxy;
    //printf("x %d y %d w %d h %d \n",m_boxx,m_boxy,m_boxwidth,m_boxheight);
    m_pic = pic;
    m_msk = msk;


    if(kind==168){

      srcw = 168;
      edge = 4;
      adjw = 160;
      mskx = 5;
      msky = 5;
      mskw = 150;
      mskh = 145;

    }else if(kind==128){
      srcw = 134;
      edge = 3;
      adjw = 128;
      mskx = 4;
      msky = 4;
      mskw = 120;
      mskh = 120;


    }

    pic_realadjw = new JMat(adjw,adjw,3,0,1);
    pic_maskadjw = new JMat(adjw,adjw,3,0,1);
    //pic_cropadjw = new JMat(adjw,adjw,3,0,1);

    msk_realadjw = new JMat(adjw,adjw,1,0,1);

}

MWorkMat::~MWorkMat(){
    matpic_orgsrcw.release();
    matpic_roirst.release();
    delete pic_realadjw;
    delete pic_maskadjw;
    delete msk_realadjw;
    if(pic_cloneadjw) delete pic_cloneadjw;
}

int MWorkMat::munet(JMat** ppic,JMat** pmsk){

    *ppic = pic_realadjw;
    *pmsk = pic_maskadjw;
    return 0;
}

int MWorkMat::premunet(){
    matpic_roisrc = cv::Mat(m_pic->cvmat(),cv::Rect(m_boxx,m_boxy,m_boxwidth,m_boxheight));
    cv::resize(matpic_roisrc , matpic_orgsrcw, cv::Size(srcw, srcw), cv::INTER_AREA);
    matpic_roiadjw = cv::Mat(matpic_orgsrcw,cv::Rect(edge,edge,adjw,adjw));
    cv::Mat cvmask = pic_maskadjw->cvmat();
    cv::Mat cvreal = pic_realadjw->cvmat();
    //printf("===matpic %d %d\n",matpic_roiadjw.cols,matpic_roiadjw.rows);
    //printf("===cvreal %d %d\n",cvreal.cols,cvreal.rows);
    //getchar();
    matpic_roiadjw.copyTo(cvreal);
    matpic_roiadjw.copyTo(cvmask);
    pic_cloneadjw = pic_realadjw->refclone(0);
    cv::rectangle(cvmask,cv::Rect(mskx,msky,mskw,mskh),cv::Scalar(0,0,0),-1);//,cv::LineTypes::FILLED);
    return 0;
}

int MWorkMat::finmunet(JMat* fgpic){
    cv::Mat cvreal = pic_realadjw->cvmat();

        //for(int k=0;k<16;k++){
            //cv::line(cvreal,cv::Point(0,k*10),cv::Point(adjw,k*10),cv::Scalar(0,255,0));
        //}
        //for(int k=0;k<16;k++){
            //cv::line(cvreal,cv::Point(k*10,0),cv::Point(k*10,adjw),cv::Scalar(0,255,0));
        //}
    cvreal.copyTo(matpic_roiadjw);
    //cv::imwrite("accpre.bmp",matpic_orgsrcw);
    if(m_msk) vtacc((uint8_t*)matpic_orgsrcw.data,srcw*srcw);
    //cv::imwrite("accend.bmp",matpic_orgsrcw);
    if(fgpic&&(fgpic->width()==srcw)){
      std::vector<cv::Mat> list;
      cv::split(matpic_orgsrcw,list);
      matmsk_roisrc = cv::Mat(m_msk->cvmat(),cv::Rect(m_boxx,m_boxy,m_boxwidth,m_boxheight));
      cv::resize(matmsk_roisrc , matmsk_orgsrcw, cv::Size(srcw, srcw), cv::INTER_AREA);
      cv::Mat rrr(srcw,srcw,CV_8UC1);
      cv::cvtColor(matmsk_orgsrcw,rrr,cv::COLOR_RGB2GRAY);
      list.push_back(rrr);
      cv::merge(list,fgpic->cvmat());
    }else{
      cv::resize(matpic_orgsrcw, matpic_roirst, cv::Size(m_boxwidth, m_boxheight), cv::INTER_AREA);
      if(fgpic){
        matpic_roisrc = cv::Mat(fgpic->cvmat(),cv::Rect(m_boxx,m_boxy,m_boxwidth,m_boxheight));
        matpic_roirst.copyTo(matpic_roisrc);
      }else{
        matpic_roirst.copyTo(matpic_roisrc);
      }
    }
    return 0;
}

int MWorkMat::alpha(JMat** preal,JMat** pimg,JMat** pmsk){
    *preal = pic_cloneadjw;
    *pimg =  pic_realadjw;
    *pmsk =  msk_realadjw;
    return 0;
}

int MWorkMat::prealpha(){
    printf("x %d y %d w %d h %d \n",m_boxx,m_boxy,m_boxwidth,m_boxheight);
    matmsk_roisrc = cv::Mat(m_msk->cvmat(),cv::Rect(m_boxx,m_boxy,m_boxwidth,m_boxheight));
    cv::resize(matmsk_roisrc , matmsk_orgsrcw, cv::Size(srcw, srcw), cv::INTER_AREA);

    matmsk_roiadjw = cv::Mat(matmsk_orgsrcw,cv::Rect(edge,edge,adjw,adjw));
    cv::Mat cvmask = msk_realadjw->cvmat();
    cv::cvtColor(matmsk_roiadjw,cvmask,cv::COLOR_RGB2GRAY);
    return 0;
}

int MWorkMat::finalpha(){
    cv::Mat cvmask = msk_realadjw->cvmat();
    cv::cvtColor(cvmask,matmsk_roiadjw,cv::COLOR_GRAY2RGB);
    //
    cv::resize(matmsk_orgsrcw, matmsk_roirst, cv::Size(m_boxwidth, m_boxheight), cv::INTER_AREA);
    matmsk_roirst.copyTo(matmsk_roisrc);
    return 0;
}

int MWorkMat::vtacc(uint8_t* buf,int count){
    /*
    int avgr = 0;
    int avgb = 0;
    int avgg = 0;
    if(1){
        uint8_t* pb = m_pic->udata();
        for(int k=0;k<10;k++){
            avgr += *pb++;
            avgg += *pb++;
            avgb += *pb++;
        }
        avgr =avgr/10 +10;
        avgg =avgg/10 -20;
        if(avgg<0)avgg=0;
        avgb =avgb/10 + 10;
    }
    */
    uint8_t* pb = buf;
    for(int k=0;k<count;k++){
        int sum  = (pb[0]+ pb[2])/2.0f;
        if(pb[1]>=sum){
            pb[1]=sum;
            //pb[0]=0;
            //pb[2]=0;
            // }else if((pb[0]<avgr)&&(pb[1]>avgg)&&(pb[2]<avgb)){
            //pb[1]=0;
            //pb[0]=0;
            //pb[2]=0;
        }
        pb+=3;
    }
    /*
    long sum = 0l;
    float  mean = sum*0.5f/count;
    uint8_t maxg = (mean>255.f)?255:mean;
    //printf("sum %ld mean %f maxg %d\n",sum,mean,maxg);
    //getchar();
    pb = buf +1;
    for(int k=0;k<count;k++){
        if(*pb>maxg){
            *pb = maxg;
        }
        pb+=3;
    }
    */
    return 0;
}

