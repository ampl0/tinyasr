#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "tinyasr.h"
static unsigned char *slurp(const char*p,size_t*n){FILE*f=fopen(p,"rb");if(!f){perror(p);exit(2);}fseek(f,0,SEEK_END);long s=ftell(f);fseek(f,0,SEEK_SET);unsigned char*b=malloc(s);*n=fread(b,1,s,f);fclose(f);return b;}
int main(int c,char**v){size_t an,vn,bn;unsigned char*asr=slurp(v[1],&an),*vad=slurp(v[2],&vn),*raw=slurp(v[3],&bn);
 char err[256]={0};tinyasr_models*m=tinyasr_load(asr,an,vad,vn,err,sizeof err);
 float*pcm=(float*)raw;size_t n=bn/4;
 tinyasr_session*s=tinyasr_session_new(m,v[4],v[4],NULL,0);
 for(size_t off=0;off<n;off+=1600){size_t k=n-off<1600?n-off:1600;
   const char*j=tinyasr_session_push(s,pcm+off,k);
   printf("  %5.1fs: %s\n",off/16000.0,j?j:"(null)");}
 printf("  finish: %s\n",tinyasr_session_finish(s));
 tinyasr_session_free(s);tinyasr_free(m);return 0;}
