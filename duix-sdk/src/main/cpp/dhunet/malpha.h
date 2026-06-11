#pragma once
#include "jmat.h"
//#include <simpleocv.h>
#include <opencv2/core/core.hpp>
#include <opencv2/highgui/highgui.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <stdio.h>

class MWorkMat{
  private:
    int     srcw = 168;
    int     edge = 4;
    int     adjw = 160;

    int     mskx = 5;
    int     msky = 5;
    int     mskw = 150;
    int     mskh = 145;
    int     m_boxx;
    int     m_boxy;
    int     m_boxwidth;
    int     m_boxheight;
    JMat*   m_pic;
    JMat*   m_msk;

    JMat*   pic_realadjw;//blendimg
    JMat*   pic_maskadjw;

    cv::Mat matpic_roisrc;//box area
    cv::Mat matpic_orgsrcw;
    cv::Mat matpic_roiadjw;
    JMat*   pic_cloneadjw;//blendimg
    cv::Mat matpic_roirst;

    //
    JMat*   msk_realadjw;

    cv::Mat matmsk_roisrc;//box area
    cv::Mat matmsk_orgsrcw;
    cv::Mat matmsk_roiadjw;

    cv::Mat matmsk_roirst;

    int vtacc(uint8_t* buf,int count);
  public:
    MWorkMat(JMat* pic,JMat* msk,const int* boxs,int kind=168);
    int premunet();
    int munet(JMat** ppic,JMat** pmsk);
    int finmunet(JMat* fgpic=NULL);
    int prealpha();
    int alpha(JMat** preal,JMat** pimg,JMat** pmsk);
    int finalpha();

    virtual ~MWorkMat();
};

