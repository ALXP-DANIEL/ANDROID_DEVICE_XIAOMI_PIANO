// SPDX-License-Identifier: BSD-2-Clause-Patent
// ESP-only GPT transaction. Shrink mounted plaintext F2FS before touching GPT.
// The included shrink helper is from slwyts/Project-SunUEFI, commit
// 1b0e201e3e7fb0737d73c41624a855482a4df7c5 (BSD-2-Clause-Patent).
#define main f2fs_main
#include "piano-resize-f2fs.c"
#undef main

static const unsigned char esp_type[16] = {
    0x28,0x73,0x2a,0xc1,0x1f,0xf8,0xd2,0x11,0xba,0x4b,0x00,0xa0,0xc9,0x3e,0xc9,0x3b
};
struct gpt {
    unsigned char primary[4096], secondary[4096], entries[32768];
    uint64_t bytes;
    uint32_t sector, length, count;
};
static void require(int ok, const char *why) {
    if (!ok) { fprintf(stderr, "%s\n", why); exit(1); }
}
static uint32_t crc(const unsigned char *p, size_t n) {
    uint32_t c = ~0U;
    while (n--) { c ^= *p++; for (unsigned j=0;j<8;j++) c=(c>>1)^((c&1)?0xedb88320U:0); }
    return ~c;
}
static void put32(unsigned char *p, uint32_t v) {
    for (unsigned i=0;i<4;i++) p[i]=(unsigned char)(v>>(i*8));
}
static void put64(unsigned char *p, uint64_t v) {
    put32(p,(uint32_t)v); put32(p+4,(uint32_t)(v>>32));
}
static void read_at(int fd, void *p, size_t n, uint64_t pos) {
    require(pread(fd,p,n,(off_t)pos)==(ssize_t)n,"Short GPT read");
}
static int empty(const unsigned char *p) {
    unsigned v=0; for(unsigned i=0;i<16;i++) v|=p[i]; return !v;
}
static int named(const unsigned char *e, const char *name) {
    size_t n=strlen(name); if(n>=36) return 0;
    for(size_t i=0;i<n;i++) if(e[56+2*i]!=(unsigned char)name[i] || e[57+2*i]) return 0;
    return !e[56+2*n] && !e[57+2*n];
}
static void header(unsigned char *h, uint64_t lba, uint64_t other) {
    require(!memcmp(h,"EFI PART",8) && le32(h+8)==0x10000 && le32(h+12)==92 &&
            le64(h+24)==lba && le64(h+32)==other,"Unsupported GPT header");
    uint32_t saved=le32(h+16); put32(h+16,0);
    require(crc(h,92)==saved,"GPT header checksum mismatch"); put32(h+16,saved);
}
static void load_gpt(int fd, struct gpt *g, int offline) {
    memset(g,0,sizeof(*g));
    if(offline) {
        struct stat st; require(!fstat(fd,&st) && S_ISREG(st.st_mode),"Offline input must be a file");
        g->bytes=(uint64_t)st.st_size; g->sector=4096;
    } else {
        require(!ioctl(fd,BLKGETSIZE64,&g->bytes) && !ioctl(fd,BLKSSZGET,&g->sector),"Cannot read disk geometry");
    }
    require(g->sector==4096 && g->bytes>10*4096 && !(g->bytes%4096),"Expected 4K-sector UFS disk");
    uint64_t end=g->bytes/g->sector-1;
    read_at(fd,g->primary,4096,4096); read_at(fd,g->secondary,4096,end*4096);
    header(g->primary,1,end); header(g->secondary,end,1);
    g->count=le32(g->primary+80);
    require(g->count && g->count<=256 && le32(g->primary+84)==128,"Unsupported GPT entries");
    g->length=g->count*128;
    require(le64(g->primary+72)==2 && le64(g->primary+40)>=2+(g->length+4095)/4096 &&
            le64(g->secondary+72)>le64(g->primary+48) &&
            le64(g->secondary+72)+(g->length+4095)/4096<=end &&
            le64(g->primary+40)<=le64(g->primary+48),"Invalid GPT bounds");
    require(!memcmp(g->primary+40,g->secondary+40,32) &&
            !memcmp(g->primary+80,g->secondary+80,12),"GPT copies disagree");
    unsigned char copy[32768];
    read_at(fd,g->entries,g->length,8192);
    read_at(fd,copy,g->length,le64(g->secondary+72)*4096);
    require(!memcmp(copy,g->entries,g->length) && crc(copy,g->length)==le32(g->primary+88),"GPT table checksum mismatch");
    for(unsigned i=0;i<g->count;i++) {
        unsigned char *e=g->entries+i*128; if(empty(e)) continue;
        uint64_t a=le64(e+32), b=le64(e+40);
        require(a<=b && a>=le64(g->primary+40) && b<=le64(g->primary+48),"Partition outside GPT bounds");
        for(unsigned j=0;j<i;j++) {
            unsigned char *f=g->entries+j*128; if(empty(f)) continue;
            require(b<le64(f+32) || a>le64(f+40),"Overlapping partitions");
        }
    }
}
static int find_esp(const struct gpt *g) {
    int found=-1;
    for(unsigned i=0;i<g->count;i++) {
        const unsigned char *e=g->entries+i*128; if(empty(e)) continue;
        int type=!memcmp(e,esp_type,16);
        if(named(e,"esp") || named(e,"sunuefi_esp")) require(type,"Named ESP has wrong GPT type");
        if(type) { require(found<0,"Multiple ESPs: refusing ambiguous update"); found=(int)i; }
    }
    return found;
}
static int write_gpt(int fd, struct gpt *g) {
    uint32_t c=crc(g->entries,g->length);
    put32(g->primary+88,c); put32(g->secondary+88,c);
    put32(g->primary+16,0); put32(g->primary+16,crc(g->primary,92));
    put32(g->secondary+16,0); put32(g->secondary+16,crc(g->secondary,92));
    return pwrite(fd,g->entries,g->length,(off_t)(le64(g->secondary+72)*4096))==(ssize_t)g->length &&
        pwrite(fd,g->secondary,4096,(off_t)(g->bytes-4096))==4096 && !fsync(fd) &&
        pwrite(fd,g->entries,g->length,8192)==(ssize_t)g->length &&
        pwrite(fd,g->primary,4096,4096)==4096 && !fsync(fd);
}

/* Receipt binds reclamation to exactly the GPT this installer created. */
struct receipt {
    char magic[16];
    struct gpt before, installed;
    uint64_t original_blocks, section_blocks;
    uint32_t checksum;
};
static void seal_gpt(struct gpt *g) {
    uint32_t c=crc(g->entries,g->length);
    put32(g->primary+88,c); put32(g->secondary+88,c);
    put32(g->primary+16,0); put32(g->primary+16,crc(g->primary,92));
    put32(g->secondary+16,0); put32(g->secondary+16,crc(g->secondary,92));
}
static void save_receipt(const char *path, const struct gpt *before,
        const struct gpt *installed, uint64_t blocks, uint64_t section) {
    struct receipt r; memset(&r,0,sizeof(r));
    memcpy(r.magic,"PIANO-ESP-R1",12); r.before=*before; r.installed=*installed;
    r.original_blocks=blocks; r.section_blocks=section;
    r.checksum=crc((unsigned char *)&r,sizeof(r));
    int fd=open(path,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);
    require(fd>=0,"Cannot create ownership receipt");
    require(write(fd,&r,sizeof(r))==(ssize_t)sizeof(r) && !fsync(fd),"Cannot persist ownership receipt");
    close(fd);
}
static int userdata_index(const struct gpt *g) {
    int found=-1;
    for(unsigned i=0;i<g->count;i++) if(!empty(g->entries+i*128) && named(g->entries+i*128,"userdata")) {
        require(found<0,"Duplicate userdata"); found=(int)i;
    }
    require(found>=0,"Missing userdata"); return found;
}
static void read_receipt(const char *path, struct receipt *r) {
    int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW); struct stat st;
    require(fd>=0 && !fstat(fd,&st) && S_ISREG(st.st_mode) && st.st_uid==0 && !(st.st_mode&077) && st.st_size==sizeof(*r),"Missing, unprotected or invalid ESP ownership receipt");
    read_at(fd,r,sizeof(*r),0); close(fd);
    uint32_t checksum=r->checksum; r->checksum=0;
    require(!memcmp(r->magic,"PIANO-ESP-R1",12) && crc((unsigned char *)r,sizeof(*r))==checksum,"Receipt checksum mismatch");
    r->checksum=checksum;
    const struct gpt *b=&r->before,*a=&r->installed;
    require(b->count && b->count<=256 && b->count==a->count && b->length==b->count*128 &&
        b->length==a->length && b->bytes==a->bytes && b->sector==4096 && a->sector==4096,
        "Receipt geometry invalid");
    int u=userdata_index(b), e=find_esp(a);
    require(find_esp(b)<0 && e>=0 && e!=u && empty(b->entries+e*128),"Receipt does not describe our fresh ESP");
    const unsigned char *old=b->entries+u*128,*now=a->entries+u*128,*esp=a->entries+e*128;
    uint64_t first=le64(old+32),last=le64(old+40),shortend=le64(now+40);
    require(first<=shortend && shortend<last && le64(esp+32)==shortend+1 &&
        le64(esp+40)<=last && le64(esp+40)-le64(esp+32)+1==131072 && named(esp,"esp") &&
        r->section_blocks>=512 && r->original_blocks>r->section_blocks &&
        r->original_blocks<=last-first+1 && (shortend-first+1)%r->section_blocks==0,
        "Receipt partition or F2FS bounds invalid");
    struct gpt expected=*b;
    put64(expected.entries+u*128+40,shortend);
    memcpy(expected.entries+e*128,esp,128); seal_gpt(&expected);
    require(!memcmp(&expected,a,sizeof(expected)),"Receipt changes unrelated GPT data");
}
/* Returns 1 if reclaimed already; no filesystem writes, including on retry. */
static int reclaim_state(const struct gpt *current, const struct receipt *r) {
    if(!memcmp(current,&r->before,sizeof(*current))) return 1;
    require(!memcmp(current,&r->installed,sizeof(*current)),"GPT differs from receipt; refusing reclamation");
    return 0;
}
static void verify_gpt(int fd, struct gpt *expected) {
    struct gpt got; load_gpt(fd,&got,0);
    require(!memcmp(&got,expected,sizeof(got)),"GPT readback failed; keep backup and do not reboot");
}
static void unmounted_device(dev_t device) {
    FILE *f=fopen("/proc/self/mountinfo","r"); require(f!=NULL,"Cannot read mount state");
    char line[8192];
    while(fgets(line,sizeof(line),f)) {
        unsigned a,b,ma,mi;
        if(sscanf(line,"%u %u %u:%u",&a,&b,&ma,&mi)==4)
            require(makedev(ma,mi)!=device,"Data mapping is still mounted");
    }
    fclose(f);
}
static void grow_check(const struct gpt *g, const struct receipt *r, const char *path, int complete) {
    int u=userdata_index(g); char physical[128],resolved[4096];
    snprintf(physical,sizeof(physical),"/dev/block/sda%d",u+1);
    require(realpath(path,resolved)!=NULL,"Cannot resolve plaintext mapping");
    int raw=open(physical,O_RDONLY|O_CLOEXEC|O_NOFOLLOW), map=open(resolved,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    struct stat a,b; uint64_t raw_bytes=0,mapped_bytes=0,blocks=0,section=0;
    uint64_t expected=(le64(g->entries+u*128+40)-le64(g->entries+u*128+32)+1)*4096;
    require(raw>=0 && map>=0 && !fstat(raw,&a) && !fstat(map,&b) &&
        S_ISBLK(a.st_mode) && S_ISBLK(b.st_mode) && !ioctl(raw,BLKGETSIZE64,&raw_bytes) &&
        !ioctl(map,BLKGETSIZE64,&mapped_bytes) && raw_bytes==expected && mapped_bytes==expected,
        "Recovery reboot required: physical/mapped userdata size differs from reclaimed GPT");
    require(a.st_rdev==b.st_rdev || mapper_is_userdata(resolved,a.st_rdev,expected),"Unverified plaintext userdata mapping");
    unmounted_device(a.st_rdev); unmounted_device(b.st_rdev);
    require(!supers(map,&blocks,&section) && section==r->section_blocks && blocks<=expected/4096,
        "Plaintext F2FS geometry invalid");
    if(complete) require(blocks<=r->original_blocks && r->original_blocks-blocks<section,
        "F2FS expansion incomplete; keep larger GPT and retry in recovery");
    else require(blocks<=r->original_blocks,"Filesystem exceeds saved target");
    close(map); close(raw);
    printf("%" PRIu64 "\n",r->original_blocks*8); /* resize.f2fs -t uses 512-byte sectors */
}
static int reclaim_command(int argc,char **argv) {
    int plan=!strcmp(argv[1],"reclaim-plan"), apply=!strcmp(argv[1],"reclaim"),
        grow=!strcmp(argv[1],"grow-check"), complete=!strcmp(argv[1],"grow-complete");
    if(!plan && !apply && !grow && !complete) return 0;
    int offline=plan && argc==5 && !strcmp(argv[4],"--offline");
    require((plan && (argc==4 || offline)) || (apply && argc==6 && !strcmp(argv[5],"--execute")) ||
        ((grow || complete) && argc==5),"Invalid reclaim arguments");
    require(offline || !strcmp(argv[2],"/dev/block/sda"),"Writes/checks only permitted on piano UFS sda");
    int fd=open(argv[2],(apply?O_RDWR:O_RDONLY)|O_CLOEXEC|O_NOFOLLOW);
    require(fd>=0,"Cannot open reclaim disk");
    struct receipt r; struct gpt current;
    read_receipt(argv[3],&r); load_gpt(fd,&current,offline);
    int done=reclaim_state(&current,&r);
    if(plan) {
        int u=userdata_index(&r.before);
        printf("%s %d %" PRIu64 "\n",done?"grow-pending":"reclaim-needed",u+1,
            (le64(r.before.entries+u*128+40)-le64(r.before.entries+u*128+32)+1)*4096);
    } else if(apply && !done) {
        int esp=find_esp(&current); char node[128],sys[256];
        snprintf(node,sizeof(node),"/dev/block/sda%d",esp+1);
        int volume=open(node,O_RDONLY|O_CLOEXEC|O_NOFOLLOW); struct stat vst; uint64_t size=0;
        require(volume>=0 && !fstat(volume,&vst) && S_ISBLK(vst.st_mode) &&
            !ioctl(volume,BLKGETSIZE64,&size) && size==536870912,"Owned ESP kernel device differs from GPT");
        snprintf(sys,sizeof(sys),"/sys/dev/block/%u:%u/start",major(vst.st_rdev),minor(vst.st_rdev));
        FILE *startfile=fopen(sys,"r"); uint64_t start=0;
        require(startfile && fscanf(startfile,"%" SCNu64,&start)==1 &&
            start/8==le64(current.entries+esp*128+32) && !(start%8),"Owned ESP kernel start differs from GPT");
        fclose(startfile); unmounted_device(vst.st_rdev); close(volume);
        int backup=open(argv[4],O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);
        require(backup>=0 && write(backup,&current,sizeof(current))==(ssize_t)sizeof(current) && !fsync(backup),"Cannot save reclaim GPT backup");
        close(backup);
        struct gpt target=r.before;
        require(write_gpt(fd,&target),"GPT reclaim write failed; do not reboot, retain receipt and backup");
        verify_gpt(fd,&target);
        (void)ioctl(fd,BLKRRPART,0);
        puts("SPACE_RETURN_PENDING: reboot recovery, decrypt data, run this ZIP again");
    } else if(grow || complete) {
        require(done,"Reclaim GPT before growing F2FS"); grow_check(&current,&r,argv[4],complete);
    } else puts("SPACE_RETURN_PENDING: GPT already expanded");
    close(fd); return 1;
}

int main(int argc, char **argv) {
    if(argc>1 && reclaim_command(argc,argv)) return 0;
    int offline=argc==4 && !strcmp(argv[1],"inspect") && !strcmp(argv[3],"--offline");
    int create=argc==6 && !strcmp(argv[1],"create") && !strcmp(argv[5],"--execute");
    require(offline || (argc==3 && !strcmp(argv[1],"inspect")) || create,
            "Usage: piano-esp inspect DISK [--offline] | create DISK BACKUP ESP.IMG --execute");
    require(!create || !strcmp(argv[2],"/dev/block/sda"),"Writes only permitted on piano UFS sda");
    int fd=open(argv[2],(create?O_RDWR:O_RDONLY)|O_CLOEXEC);
    require(fd>=0,"Cannot open disk"); struct gpt old, next, check;
    load_gpt(fd,&old,offline); int idx=find_esp(&old);
    if(idx>=0) { printf("%d\n",idx+1); close(fd); return 0; }
    if(!create) { puts("0"); close(fd); return 0; }
    int u=-1, spare=-1;
    for(unsigned i=0;i<old.count;i++) {
        unsigned char *e=old.entries+i*128;
        if(empty(e)) { if(spare<0) spare=(int)i; continue; }
        if(named(e,"userdata")) { require(u<0,"Duplicate userdata"); u=(int)i; }
    }
    require(u>=0 && spare>=0,"Missing userdata or free GPT entry");
    unsigned char *ue=old.entries+u*128;
    uint64_t first=le64(ue+32), last=le64(ue+40), disk_last=le64(old.primary+48);
    for(unsigned i=0;i<old.count;i++) {
        unsigned char *e=old.entries+i*128;
        if(!empty(e) && (int)i!=u) require(le64(e+40)<first,"userdata is not the last partition");
    }
    char mapping[4096], rawpath[4096], expected[128];
    snprintf(expected,sizeof(expected),"/dev/block/sda%d",u+1);
    require(!mounted_data(mapping) && realpath("/dev/block/by-name/userdata",rawpath) &&
            !strcmp(expected,rawpath),"Need decrypted mounted F2FS /data on this userdata");
    int map=open(mapping,O_RDONLY|O_CLOEXEC); uint64_t blocks, section;
    require(map>=0 && !supers(map,&blocks,&section),"Cannot read plaintext F2FS superblocks"); close(map);
    uint64_t esp_sectors=512ULL*1024*1024/4096;
    require(disk_last>esp_sectors && disk_last-esp_sectors>first,"Disk too small");
    uint64_t limit=((disk_last+1-esp_sectors-first)/section)*section;
    require(limit>2*section && first+limit-1<last,"Cannot shrink userdata for ESP");
    require(first+limit+esp_sectors-1<=last,"ESP would extend beyond original userdata extent");
    int image=open(argv[4],O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    struct stat image_stat; unsigned char fat_header[512];
    require(image>=0 && !fstat(image,&image_stat) && S_ISREG(image_stat.st_mode) &&
            image_stat.st_size>=512 && image_stat.st_size<=512LL*1024*1024,"Invalid ESP image size");
    read_at(image,fat_header,512,0);
    require(fat_header[510]==0x55 && fat_header[511]==0xaa &&
            !memcmp(fat_header+54,"FAT16   ",8),"Expected packaged FAT16 ESP image");
    int backup=open(argv[3],O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);
    require(backup>=0 && write(backup,&old,sizeof(old))==(ssize_t)sizeof(old) && !fsync(backup),"Cannot persist GPT backup"); close(backup);
    char bytes[32]; snprintf(bytes,sizeof(bytes),"%" PRIu64,limit*4096);
    char *args[]={"piano-resize-f2fs","shrink","--target-bytes",bytes,"--execute",NULL};
    // Keep stdout machine-readable (only the resulting ESP index).
    int saved=dup(STDOUT_FILENO); require(saved>=0 && dup2(STDERR_FILENO,STDOUT_FILENO)>=0,"Cannot redirect shrink output");
    require(!f2fs_main(5,args),"F2FS shrink failed: GPT unchanged"); fflush(stdout);
    require(dup2(saved,STDOUT_FILENO)>=0,"Cannot restore output"); close(saved);
    load_gpt(fd,&check,0); require(!memcmp(&old,&check,sizeof(old)),"GPT changed during shrink; refusing write");
    next=old; put64(next.entries+u*128+40,first+limit-1);
    unsigned char *e=next.entries+spare*128; memset(e,0,128); memcpy(e,esp_type,16);
    int random=open("/dev/urandom",O_RDONLY); require(random>=0,"Cannot generate GUID"); read_at(random,e+16,16,0); close(random);
    put64(e+32,first+limit); put64(e+40,first+limit+esp_sectors-1);
    e[56]='e'; e[58]='s'; e[60]='p';
    int ok=write_gpt(fd,&next);
    if(ok) {
        unsigned char table[32768], h[4096];
        ok=pread(fd,table,next.length,8192)==(ssize_t)next.length && !memcmp(table,next.entries,next.length) &&
            pread(fd,table,next.length,(off_t)(le64(next.secondary+72)*4096))==(ssize_t)next.length && !memcmp(table,next.entries,next.length) &&
            pread(fd,h,4096,4096)==4096 && !memcmp(h,next.primary,4096) &&
            pread(fd,h,4096,(off_t)(next.bytes-4096))==4096 && !memcmp(h,next.secondary,4096);
    }
    if(!ok) { require(write_gpt(fd,&old),"GPT write and rollback failed: do not reboot"); require(0,"GPT write failed; restored previous table"); }
    char receipt_path[4096];
    require(snprintf(receipt_path,sizeof(receipt_path),"%s.receipt",argv[3])<(int)sizeof(receipt_path),"Receipt path too long");
    save_receipt(receipt_path,&old,&next,blocks,section);
    // Write the new FAT volume by its verified GPT extent: mounted userdata
    // can prevent kernel partition reread until reboot. Never use this for an
    // existing ESP, whose files must be preserved.
    unsigned char buffer[65536], verify[65536];
    uint64_t pos=0, offset=(first+limit)*4096;
    while(pos<(uint64_t)image_stat.st_size) {
        size_t n=(uint64_t)image_stat.st_size-pos>sizeof(buffer)?sizeof(buffer):(size_t)((uint64_t)image_stat.st_size-pos);
        read_at(image,buffer,n,pos);
        require(pwrite(fd,buffer,n,(off_t)(offset+pos))==(ssize_t)n && !fsync(fd),"ESP image write failed; boot not patched");
        read_at(fd,verify,n,offset+pos); require(!memcmp(buffer,verify,n),"ESP image verification failed; boot not patched"); pos+=n;
    }
    close(image);
    // Mounted userdata usually prevents BLKRRPART. Recovery must expose the new
    // partition before formatting; installer retries its own newly-created ESP.
    (void)ioctl(fd,BLKRRPART,0); close(fd); printf("%d\n",spare+1); return 0;
}
