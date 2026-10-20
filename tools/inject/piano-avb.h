/* SPDX-License-Identifier: BSD-2-Clause-Patent */
/* Fresh AVB 1.0 hash metadata. Algorithm NONE is for unlocked devices only.
 * Never copy a signature or digest from the pre-modification image. */
static uint32_t avb_rotr(uint32_t x, unsigned n) { return (x >> n) | (x << (32-n)); }
static void avb_be32(uint8_t *p, uint32_t v)
{ for (unsigned i=0;i<4;i++) p[i]=(uint8_t)(v >> (24-8*i)); }
static void avb_be64(uint8_t *p, uint64_t v)
{ for (unsigned i=0;i<8;i++) p[i]=(uint8_t)(v >> (56-8*i)); }
static void avb_sha256(const uint8_t *data, size_t len, uint8_t digest[32])
{
 static const uint32_t k[64]={
  0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
  0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
  0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
  0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
  0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
  0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
  0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
  0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
 uint32_t h[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
 size_t blocks=len/64+1+((len%64)>=56);
 for(size_t block=0;block<blocks;block++) {
  uint8_t buf[64]={0}; size_t off=block*64;
  for(size_t j=0;j<64;j++) {
   size_t n=off+j;
   if(n<len) buf[j]=data[n]; else if(n==len) buf[j]=0x80;
  }
  if(block==blocks-1) avb_be64(buf+56,(uint64_t)len*8);
  uint32_t w[64];
  for(unsigned i=0;i<16;i++) w[i]=(uint32_t)buf[4*i]<<24 | (uint32_t)buf[4*i+1]<<16 | (uint32_t)buf[4*i+2]<<8 | buf[4*i+3];
  for(unsigned i=16;i<64;i++) {
   uint32_t x=w[i-15],y=w[i-2];
   w[i]=w[i-16]+(avb_rotr(x,7)^avb_rotr(x,18)^(x>>3))+w[i-7]+(avb_rotr(y,17)^avb_rotr(y,19)^(y>>10));
  }
  uint32_t a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],v=h[7];
  for(unsigned i=0;i<64;i++) {
   uint32_t t1=v+(avb_rotr(e,6)^avb_rotr(e,11)^avb_rotr(e,25))+((e&f)^(~e&g))+k[i]+w[i];
   uint32_t t2=(avb_rotr(a,2)^avb_rotr(a,13)^avb_rotr(a,22))+((a&b)^(a&c)^(b&c));
   v=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
  }
  h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=d;h[4]+=e;h[5]+=f;h[6]+=g;h[7]+=v;
 }
 for(unsigned i=0;i<8;i++) avb_be32(digest+4*i,h[i]);
}
static uint64_t avb_rd64(const uint8_t *p)
{ uint64_t v=0;for(unsigned i=0;i<8;i++)v=(v<<8)|p[i];return v; }
static uint8_t *piano_avb_footer(uint8_t *image, size_t size, size_t partition,
                                const uint8_t *original)
{
 size_t offset=(size+4095)&~(size_t)4095, props_size=0;
 uint8_t props[65536]; uint64_t rollback=0; uint32_t rollback_location=0;
 /* Keep authenticated boot-version properties supplied to KeyMint. Their
  * values describe this unchanged Android build, not the injected payload.
  * Old hashes, keys and signatures must not be reused. */
 if(partition>=64 && !memcmp(original+partition-64,"AVBf",4)) {
  const uint8_t *f=original+partition-64;
  uint64_t vo=avb_rd64(f+20),vs=avb_rd64(f+28);
  if(vo>partition-64 || vs>partition-64-vo || vs<256 || memcmp(original+vo,"AVB0",4)) goto invalid;
  const uint8_t *old=original+vo;
  uint64_t auth=avb_rd64(old+12),aux=avb_rd64(old+20),ds=avb_rd64(old+104),dof=avb_rd64(old+96);
  if(auth>vs-256 || aux>vs-256-auth || dof>aux || ds>aux-dof) goto invalid;
  const uint8_t *d=old+256+auth+dof;
  rollback=avb_rd64(old+112);
  rollback_location=(uint32_t)old[124]<<24|(uint32_t)old[125]<<16|(uint32_t)old[126]<<8|old[127];
  while(ds) {
   if(ds<16) goto invalid;
   uint64_t n=avb_rd64(d+8);
   if(n>ds-16 || n%8) goto invalid;
   if(avb_rd64(d)==0) {
    if(n+16>sizeof(props)-props_size) goto invalid;
    memcpy(props+props_size,d,n+16);props_size+=n+16;
   }
   d+=n+16;ds-=n+16;
  }
 }
 size_t descriptors=168+props_size,aux_size=(descriptors+63)&~(size_t)63;
 size_t vbmeta_size=256+aux_size;
 if(partition%4096 || partition<8192 || offset>partition-4096 || vbmeta_size>partition-4096-offset) {
  fprintf(stderr,"piano-inject: insufficient partition space for AVB metadata\n");exit(1);
 }
 uint8_t *out=calloc(1,partition);
 if(!out) { fprintf(stderr,"piano-inject: AVB allocation failed\n");exit(1); }
 memcpy(out,image,size);free(image);
 uint8_t *v=out+offset,*desc=v+256,*footer=out+partition-64;
 memcpy(v,"AVB0",4);avb_be32(v+4,1);avb_be64(v+20,aux_size);
 avb_be64(v+104,descriptors);avb_be64(v+112,rollback);avb_be32(v+124,rollback_location);
 memcpy(v+128,"piano-inject AVB 1.0",20);
 avb_be64(desc,2);avb_be64(desc+8,152);avb_be64(desc+16,size);
 memcpy(desc+24,"sha256",6);avb_be32(desc+56,4);avb_be32(desc+64,32);
 memcpy(desc+132,"boot",4);avb_sha256(out,size,desc+136);
 memcpy(desc+168,props,props_size);
 memcpy(footer,"AVBf",4);avb_be32(footer+4,1);
 avb_be64(footer+12,size);avb_be64(footer+20,offset);avb_be64(footer+28,vbmeta_size);
 return out;
invalid:
 fprintf(stderr,"piano-inject: malformed original AVB metadata\n");exit(1);
}
