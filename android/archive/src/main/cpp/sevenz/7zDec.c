/* 7zDec.c -- Decoding from 7z folder
: Igor Pavlov : Public domain */

#include "Precomp.h"

#include <string.h>

/* #define Z7_PPMD_SUPPORT */

#include "7z.h"
#include "7zCrc.h"

#include "Bcj2.h"
#include "Bra.h"
#include "CpuArch.h"
#include "Delta.h"
#include "LzmaDec.h"
#include "Lzma2Dec.h"
#ifdef Z7_PPMD_SUPPORT
#include "Ppmd7.h"
#endif

/* --- 7z AES-256 decryption (added; the reference sample ships no crypto) ---
   A password-protected 7z encrypts each content folder with AES-256-CBC, the
   key derived from the password and a per-archive salt by iterated SHA-256.
   The reference decoder has no AES, so it is added here: an AES folder is
   decrypted and its inner method decoded from the plaintext. The password,
   as UTF-16LE (which is what 7z hashes), is set before extraction. This is
   for content encryption -- an archive whose header is encrypted does not
   list, and is not handled. */
#include <stdlib.h>
#include <stdint.h>
#include "Aes.h"
#include "Sha256.h"

#define k_AES 0x06F10701
/* An AES folder failed to decode: almost always a wrong or missing password. */
#define SZ_ERROR_7Z_AES 100

static Byte g_sevenZ_password[512];   /* UTF-16LE, up to 256 characters */
static size_t g_sevenZ_passwordLen = 0;

/* Called from the JNI bridge (C++), so keep C linkage across that boundary. */
#ifdef __cplusplus
extern "C"
#endif
void SevenZ_SetPassword(const Byte *utf16le, size_t len)
{
  if (len > sizeof(g_sevenZ_password))
    len = sizeof(g_sevenZ_password);
  if (len != 0)
    memcpy(g_sevenZ_password, utf16le, len);
  g_sevenZ_passwordLen = len;
}

#define k_Copy 0
#ifndef Z7_NO_METHOD_LZMA2
#define k_LZMA2 0x21
#endif
#define k_LZMA  0x30101
#define k_BCJ2  0x303011B

#if !defined(Z7_NO_METHODS_FILTERS)
#define Z7_USE_BRANCH_FILTER
#endif

#if !defined(Z7_NO_METHODS_FILTERS) || \
     defined(Z7_USE_NATIVE_BRANCH_FILTER) && defined(MY_CPU_ARM64)
#define Z7_USE_FILTER_ARM64
#ifndef Z7_USE_BRANCH_FILTER
#define Z7_USE_BRANCH_FILTER
#endif
#define k_ARM64 0xa
#endif

#if !defined(Z7_NO_METHODS_FILTERS) || \
     defined(Z7_USE_NATIVE_BRANCH_FILTER) && defined(MY_CPU_ARMT)
#define Z7_USE_FILTER_ARMT
#ifndef Z7_USE_BRANCH_FILTER
#define Z7_USE_BRANCH_FILTER
#endif
#define k_ARMT  0x3030701
#endif

#ifndef Z7_NO_METHODS_FILTERS
#define k_Delta 3
#define k_RISCV 0xb
#define k_BCJ   0x3030103
#define k_PPC   0x3030205
#define k_IA64  0x3030401
#define k_ARM   0x3030501
#define k_SPARC 0x3030805
#endif

#ifdef Z7_PPMD_SUPPORT

#define k_PPMD 0x30401

typedef struct
{
  IByteIn vt;
  const Byte *cur;
  const Byte *end;
  const Byte *begin;
  UInt64 processed;
  BoolInt extra;
  SRes res;
  ILookInStreamPtr inStream;
} CByteInToLook;

static Byte ReadByte(IByteInPtr pp)
{
  Z7_CONTAINER_FROM_VTBL_TO_DECL_VAR_pp_vt_p(CByteInToLook)
  if (p->cur != p->end)
    return *p->cur++;
  if (p->res == SZ_OK)
  {
    size_t size = (size_t)(p->cur - p->begin);
    p->processed += size;
    p->res = ILookInStream_Skip(p->inStream, size);
    size = (1 << 25);
    p->res = ILookInStream_Look(p->inStream, (const void **)&p->begin, &size);
    p->cur = p->begin;
    p->end = p->begin + size;
    if (size != 0)
      return *p->cur++;
  }
  p->extra = True;
  return 0;
}

static SRes SzDecodePpmd(const Byte *props, unsigned propsSize, UInt64 inSize, ILookInStreamPtr inStream,
    Byte *outBuffer, SizeT outSize, ISzAllocPtr allocMain)
{
  CPpmd7 *ppmd;
  SRes res;
  unsigned order;
  UInt32 memSize;

  if (propsSize != 5)
    return SZ_ERROR_UNSUPPORTED;
  order = props[0];
  memSize = GetUi32(props + 1);
  if (order < PPMD7_MIN_ORDER ||
      order > PPMD7_MAX_ORDER ||
      memSize < PPMD7_MIN_MEM_SIZE ||
      memSize > PPMD7_MAX_MEM_SIZE)
    return SZ_ERROR_UNSUPPORTED;
  if ((ppmd = (CPpmd7 *)ISzAlloc_Alloc(allocMain, sizeof(CPpmd7))) == NULL)
    return SZ_ERROR_MEM;
  Ppmd7_Construct(ppmd);
  res = SZ_ERROR_MEM;
  if (Ppmd7_Alloc(ppmd, memSize, allocMain))
  {
    CByteInToLook s;
    s.vt.Read = ReadByte;
    s.inStream = inStream;
    s.begin = s.end = s.cur = NULL;
    s.extra = False;
    s.res = SZ_OK;
    s.processed = 0;

    Ppmd7_Init(ppmd, order);
    ppmd->rc.dec.Stream = &s.vt;
    res = SZ_ERROR_DATA;
    if (Ppmd7z_RangeDec_Init(&ppmd->rc.dec) && !s.extra)
    {
      Byte *buf = outBuffer;
      const Byte *lim = buf + outSize;
      for (; buf != lim; buf++)
      {
        int sym = Ppmd7z_DecodeSymbol(ppmd);
        if (s.extra || sym < 0)
          break;
        *buf = (Byte)sym;
      }
      if (buf == lim)
        if (Ppmd7z_RangeDec_IsFinishedOK(&ppmd->rc.dec)
            // || (Ppmd7z_DecodeSymbol(&ppmd) == PPMD7_SYM_END && Ppmd7z_RangeDec_IsFinishedOK(&ppmd.rc.dec))
            )
          res = SZ_OK;
    }
    if (s.extra)
      res = (s.res != SZ_OK ? s.res : SZ_ERROR_DATA);
    else if (s.processed + (size_t)(s.cur - s.begin) != inSize)
      res = SZ_ERROR_DATA;
    Ppmd7_Free(ppmd, allocMain);
  }
  ISzAlloc_Free(allocMain, ppmd);
  return res;
}

#endif


static SRes SzDecodeLzma(const Byte *props, unsigned propsSize, UInt64 inSize, ILookInStreamPtr inStream,
    Byte *outBuffer, SizeT outSize, ISzAllocPtr allocMain)
{
  CLzmaDec state;
  SRes res = SZ_OK;

  LzmaDec_CONSTRUCT(&state)
  RINOK(LzmaDec_AllocateProbs(&state, props, propsSize, allocMain))
  state.dic = outBuffer;
  state.dicBufSize = outSize;
  LzmaDec_Init(&state);

  for (;;)
  {
    const void *inBuf = NULL;
    size_t lookahead = (1 << 18);
    if (lookahead > inSize)
      lookahead = (size_t)inSize;
    res = ILookInStream_Look(inStream, &inBuf, &lookahead);
    if (res != SZ_OK)
      break;

    {
      SizeT inProcessed = (SizeT)lookahead, dicPos = state.dicPos;
      ELzmaStatus status;
      res = LzmaDec_DecodeToDic(&state, outSize, (const Byte *)inBuf, &inProcessed, LZMA_FINISH_END, &status);
      lookahead -= inProcessed;
      inSize -= inProcessed;
      if (res != SZ_OK)
        break;

      if (status == LZMA_STATUS_FINISHED_WITH_MARK)
      {
        if (outSize != state.dicPos || inSize != 0)
          res = SZ_ERROR_DATA;
        break;
      }

      if (outSize == state.dicPos && inSize == 0 && status == LZMA_STATUS_MAYBE_FINISHED_WITHOUT_MARK)
        break;

      if (inProcessed == 0 && dicPos == state.dicPos)
      {
        res = SZ_ERROR_DATA;
        break;
      }

      res = ILookInStream_Skip(inStream, inProcessed);
      if (res != SZ_OK)
        break;
    }
  }

  LzmaDec_FreeProbs(&state, allocMain);
  return res;
}


#ifndef Z7_NO_METHOD_LZMA2

static SRes SzDecodeLzma2(const Byte *props, unsigned propsSize, UInt64 inSize, ILookInStreamPtr inStream,
    Byte *outBuffer, SizeT outSize, ISzAllocPtr allocMain)
{
  CLzma2Dec state;
  SRes res = SZ_OK;

  Lzma2Dec_CONSTRUCT(&state)
  if (propsSize != 1)
    return SZ_ERROR_DATA;
  RINOK(Lzma2Dec_AllocateProbs(&state, props[0], allocMain))
  state.decoder.dic = outBuffer;
  state.decoder.dicBufSize = outSize;
  Lzma2Dec_Init(&state);

  for (;;)
  {
    const void *inBuf = NULL;
    size_t lookahead = (1 << 18);
    if (lookahead > inSize)
      lookahead = (size_t)inSize;
    res = ILookInStream_Look(inStream, &inBuf, &lookahead);
    if (res != SZ_OK)
      break;

    {
      SizeT inProcessed = (SizeT)lookahead, dicPos = state.decoder.dicPos;
      ELzmaStatus status;
      res = Lzma2Dec_DecodeToDic(&state, outSize, (const Byte *)inBuf, &inProcessed, LZMA_FINISH_END, &status);
      lookahead -= inProcessed;
      inSize -= inProcessed;
      if (res != SZ_OK)
        break;

      if (status == LZMA_STATUS_FINISHED_WITH_MARK)
      {
        if (outSize != state.decoder.dicPos || inSize != 0)
          res = SZ_ERROR_DATA;
        break;
      }

      if (inProcessed == 0 && dicPos == state.decoder.dicPos)
      {
        res = SZ_ERROR_DATA;
        break;
      }

      res = ILookInStream_Skip(inStream, inProcessed);
      if (res != SZ_OK)
        break;
    }
  }

  Lzma2Dec_FreeProbs(&state, allocMain);
  return res;
}

#endif


static SRes SzDecodeCopy(UInt64 inSize, ILookInStreamPtr inStream, Byte *outBuffer)
{
  while (inSize > 0)
  {
    const void *inBuf;
    size_t curSize = (1 << 18);
    if (curSize > inSize)
      curSize = (size_t)inSize;
    RINOK(ILookInStream_Look(inStream, &inBuf, &curSize))
    if (curSize == 0)
      return SZ_ERROR_INPUT_EOF;
    memcpy(outBuffer, inBuf, curSize);
    outBuffer += curSize;
    inSize -= curSize;
    RINOK(ILookInStream_Skip(inStream, curSize))
  }
  return SZ_OK;
}

static BoolInt IS_MAIN_METHOD(UInt32 m)
{
  switch (m)
  {
    case k_Copy:
    case k_LZMA:
  #ifndef Z7_NO_METHOD_LZMA2
    case k_LZMA2:
  #endif
  #ifdef Z7_PPMD_SUPPORT
    case k_PPMD:
  #endif
      return True;
    default:
      return False;
  }
}

static BoolInt IS_SUPPORTED_CODER(const CSzCoderInfo *c)
{
  return
      c->NumStreams == 1
      /* && c->MethodID <= (UInt32)0xFFFFFFFF */
      && IS_MAIN_METHOD((UInt32)c->MethodID);
}

#define IS_BCJ2(c) ((c)->MethodID == k_BCJ2 && (c)->NumStreams == 4)

static SRes CheckSupportedFolder(const CSzFolder *f)
{
  if (f->NumCoders < 1 || f->NumCoders > 4)
    return SZ_ERROR_UNSUPPORTED;
  /* An AES folder is validated and decoded by SzDecodeAesFolder, which the
     folder decode reaches before walking the coder graph below. */
  {
    UInt32 ci;
    for (ci = 0; ci < f->NumCoders; ci++)
      if (f->Coders[ci].MethodID == k_AES)
        return SZ_OK;
  }
  if (!IS_SUPPORTED_CODER(&f->Coders[0]))
    return SZ_ERROR_UNSUPPORTED;
  if (f->NumCoders == 1)
  {
    if (f->NumPackStreams != 1 || f->PackStreams[0] != 0 || f->NumBonds != 0)
      return SZ_ERROR_UNSUPPORTED;
    return SZ_OK;
  }
  
  
  #if defined(Z7_USE_BRANCH_FILTER)

  if (f->NumCoders == 2)
  {
    const CSzCoderInfo *c = &f->Coders[1];
    if (
        /* c->MethodID > (UInt32)0xFFFFFFFF || */
        c->NumStreams != 1
        || f->NumPackStreams != 1
        || f->PackStreams[0] != 0
        || f->NumBonds != 1
        || f->Bonds[0].InIndex != 1
        || f->Bonds[0].OutIndex != 0)
      return SZ_ERROR_UNSUPPORTED;
    switch ((UInt32)c->MethodID)
    {
    #if !defined(Z7_NO_METHODS_FILTERS)
      case k_Delta:
      case k_BCJ:
      case k_PPC:
      case k_IA64:
      case k_SPARC:
      case k_ARM:
      case k_RISCV:
    #endif
    #ifdef Z7_USE_FILTER_ARM64
      case k_ARM64:
    #endif
    #ifdef Z7_USE_FILTER_ARMT
      case k_ARMT:
    #endif
        break;
      default:
        return SZ_ERROR_UNSUPPORTED;
    }
    return SZ_OK;
  }

  #endif

  
  if (f->NumCoders == 4)
  {
    if (!IS_SUPPORTED_CODER(&f->Coders[1])
        || !IS_SUPPORTED_CODER(&f->Coders[2])
        || !IS_BCJ2(&f->Coders[3]))
      return SZ_ERROR_UNSUPPORTED;
    if (f->NumPackStreams != 4
        || f->PackStreams[0] != 2
        || f->PackStreams[1] != 6
        || f->PackStreams[2] != 1
        || f->PackStreams[3] != 0
        || f->NumBonds != 3
        || f->Bonds[0].InIndex != 5 || f->Bonds[0].OutIndex != 0
        || f->Bonds[1].InIndex != 4 || f->Bonds[1].OutIndex != 1
        || f->Bonds[2].InIndex != 3 || f->Bonds[2].OutIndex != 2)
      return SZ_ERROR_UNSUPPORTED;
    return SZ_OK;
  }
  
  return SZ_ERROR_UNSUPPORTED;
}






/* The AES-256 key and IV from the coder's properties and the set password.
   The property layout and the SHA-256 key schedule follow 7-Zip's own
   7zAes.cpp exactly. */
static void Sz7z_DeriveKey(const Byte *props, unsigned propsSize, Byte key[32], Byte iv[16])
{
  unsigned numCyclesPower = 0, saltSize = 0, ivSize = 0, i;
  const Byte *salt = props + 2;
  memset(iv, 0, 16);
  memset(key, 0, 32);
  if (propsSize == 0)
    return;
  {
    unsigned b0 = props[0];
    numCyclesPower = b0 & 0x3F;
    if ((b0 & 0xC0) != 0 && propsSize >= 2)
    {
      unsigned b1 = props[1];
      saltSize = ((b0 >> 7) & 1) + (b1 >> 4);
      ivSize   = ((b0 >> 6) & 1) + (b1 & 0x0F);
      for (i = 0; i < ivSize && i < 16; i++)
        iv[i] = props[2 + saltSize + i];
    }
  }
  if (numCyclesPower == 0x3F)
  {
    unsigned pos = 0;
    for (; pos < saltSize && pos < 32; pos++) key[pos] = salt[pos];
    for (i = 0; i < g_sevenZ_passwordLen && pos < 32; i++) key[pos++] = g_sevenZ_password[i];
    return;
  }
  {
    CSha256 sha;
    size_t bufSize = 8 + saltSize + g_sevenZ_passwordLen;
    Byte *buf = (Byte *)malloc(bufSize);
    UInt64 rounds, r;
    if (!buf)
      return;
    if (saltSize != 0)
      memcpy(buf, salt, saltSize);
    if (g_sevenZ_passwordLen != 0)
      memcpy(buf + saltSize, g_sevenZ_password, g_sevenZ_passwordLen);
    memset(buf + bufSize - 8, 0, 8);
    Sha256_Init(&sha);
    rounds = (UInt64)1 << numCyclesPower;
    for (r = 0; r < rounds; r++)
    {
      Byte *ctr = buf + bufSize - 8;
      unsigned k;
      Sha256_Update(&sha, buf, bufSize);
      for (k = 0; k < 8; k++) { if (++ctr[k] != 0) break; }
    }
    Sha256_Final(&sha, key);
    free(buf);
  }
}

/* A read stream over a memory buffer, so the plaintext can feed the inner
   decoder through the same ILookInStream the file ones use. */
typedef struct { ISeekInStream vt; const Byte *data; size_t size; size_t pos; } CMemInStream;

static SRes MemInStream_Read(ISeekInStreamPtr pp, void *buf, size_t *size)
{
  CMemInStream *s = (CMemInStream *)pp;
  size_t n = *size, avail = s->size - s->pos;
  if (n > avail) n = avail;
  if (n != 0) memcpy(buf, s->data + s->pos, n);
  s->pos += n;
  *size = n;
  return SZ_OK;
}

static SRes MemInStream_Seek(ISeekInStreamPtr pp, Int64 *pos, ESzSeek origin)
{
  CMemInStream *s = (CMemInStream *)pp;
  Int64 p = *pos;
  if (origin == SZ_SEEK_CUR) p += (Int64)s->pos;
  else if (origin == SZ_SEEK_END) p += (Int64)s->size;
  if (p < 0 || (UInt64)p > (UInt64)s->size) return SZ_ERROR_PARAM;
  s->pos = (size_t)p;
  *pos = p;
  return SZ_OK;
}

/* Decrypts an AES folder's packed stream, then decodes its one inner method
   from the plaintext. Handles the common shape 7-Zip writes for encryption:
   AES over a single Copy/LZMA/LZMA2/PPMd coder. Rarer chains (a branch
   filter under AES) return unsupported. */
static SRes SzDecodeAesFolder(const CSzFolder *folder, const Byte *propsData,
    const UInt64 *unpackSizes, const UInt64 *packPositions,
    ILookInStreamPtr inStream, UInt64 startPos,
    Byte *outBuffer, SizeT outSize, ISzAllocPtr allocMain)
{
  UInt32 ci, aesCi = folder->NumCoders, innerCi = folder->NumCoders;
  const CSzCoderInfo *aes, *inner;
  Byte key[32], iv[16];
  UInt32 aesbuf[AES_NUM_IVMRK_WORDS + 4];
  UInt32 *p;
  Byte *enc;
  UInt64 encSize, decSize;
  SRes res;
  CMemInStream mem;
  CLookToRead2 look;

  if (folder->NumCoders != 2 || folder->NumPackStreams != 1)
    return SZ_ERROR_UNSUPPORTED;
  for (ci = 0; ci < folder->NumCoders; ci++)
  {
    if (folder->Coders[ci].MethodID == k_AES) aesCi = ci;
    else innerCi = ci;
  }
  if (aesCi == folder->NumCoders || innerCi == folder->NumCoders)
    return SZ_ERROR_UNSUPPORTED;
  aes = &folder->Coders[aesCi];
  inner = &folder->Coders[innerCi];

  encSize = packPositions[1] - packPositions[0];
  decSize = unpackSizes[aesCi];         /* the plaintext (inner-compressed) size */
  if ((encSize & 15) != 0 || decSize > encSize)
    return SZ_ERROR_DATA;

  enc = (Byte *)ISzAlloc_Alloc(allocMain, encSize != 0 ? (size_t)encSize : 1);
  if (!enc)
    return SZ_ERROR_MEM;

  res = LookInStream_SeekTo(inStream, startPos + packPositions[0]);
  if (res == SZ_OK)
    res = SzDecodeCopy(encSize, inStream, enc);
  if (res != SZ_OK)
  {
    ISzAlloc_Free(allocMain, enc);
    return res;
  }

  Sz7z_DeriveKey(propsData + aes->PropsOffset, aes->PropsSize, key, iv);
  AesGenTables();
  p = (UInt32 *)(void *)(((uintptr_t)aesbuf + 15) & ~(uintptr_t)15);
  Aes_SetKey_Dec(p + 4, key, 32);
  AesCbc_Init(p, iv);
  if (encSize != 0)
    g_AesCbc_Decode(p, enc, (size_t)encSize / 16);

  mem.vt.Read = MemInStream_Read;
  mem.vt.Seek = MemInStream_Seek;
  mem.data = enc;
  mem.size = (size_t)decSize;
  mem.pos = 0;
  look.buf = (Byte *)ISzAlloc_Alloc(allocMain, 1 << 16);
  if (!look.buf)
  {
    ISzAlloc_Free(allocMain, enc);
    return SZ_ERROR_MEM;
  }
  LookToRead2_CreateVTable(&look, False);
  look.bufSize = 1 << 16;
  look.realStream = &mem.vt;
  LookToRead2_INIT(&look)

  switch ((UInt32)inner->MethodID)
  {
    case k_Copy:
      if (decSize != outSize) res = SZ_ERROR_DATA;
      else { memcpy(outBuffer, enc, (size_t)outSize); res = SZ_OK; }
      break;
    case k_LZMA:
      res = SzDecodeLzma(propsData + inner->PropsOffset, inner->PropsSize, decSize, &look.vt, outBuffer, outSize, allocMain);
      break;
#ifndef Z7_NO_METHOD_LZMA2
    case k_LZMA2:
      res = SzDecodeLzma2(propsData + inner->PropsOffset, inner->PropsSize, decSize, &look.vt, outBuffer, outSize, allocMain);
      break;
#endif
#ifdef Z7_PPMD_SUPPORT
    case k_PPMD:
      res = SzDecodePpmd(propsData + inner->PropsOffset, inner->PropsSize, decSize, &look.vt, outBuffer, outSize, allocMain);
      break;
#endif
    default:
      res = SZ_ERROR_UNSUPPORTED;
  }

  ISzAlloc_Free(allocMain, look.buf);
  ISzAlloc_Free(allocMain, enc);
  /* A wrong password decrypts to garbage the inner method rejects as bad
     data. Report it as the crypto failure it almost always is, so the caller
     can re-ask -- a truly unsupported inner method stays that. */
  if (res == SZ_ERROR_DATA)
    return SZ_ERROR_7Z_AES;
  return res;
}

static SRes SzFolder_Decode2(const CSzFolder *folder,
    const Byte *propsData,
    const UInt64 *unpackSizes,
    const UInt64 *packPositions,
    ILookInStreamPtr inStream, UInt64 startPos,
    Byte *outBuffer, SizeT outSize, ISzAllocPtr allocMain,
    Byte *tempBuf[])
{
  UInt32 ci;
  SizeT tempSizes[3] = { 0, 0, 0};
  SizeT tempSize3 = 0;
  Byte *tempBuf3 = 0;

  RINOK(CheckSupportedFolder(folder))

  /* An encrypted folder is decrypted and decoded on its own path, above the
     coder graph the reference sample walks. */
  for (ci = 0; ci < folder->NumCoders; ci++)
    if (folder->Coders[ci].MethodID == k_AES)
      return SzDecodeAesFolder(folder, propsData, unpackSizes, packPositions,
          inStream, startPos, outBuffer, outSize, allocMain);

  for (ci = 0; ci < folder->NumCoders; ci++)
  {
    const CSzCoderInfo *coder = &folder->Coders[ci];

    if (IS_MAIN_METHOD((UInt32)coder->MethodID))
    {
      UInt32 si = 0;
      UInt64 offset;
      UInt64 inSize;
      Byte *outBufCur = outBuffer;
      SizeT outSizeCur = outSize;
      if (folder->NumCoders == 4)
      {
        const UInt32 indices[] = { 3, 2, 0 };
        const UInt64 unpackSize = unpackSizes[ci];
        si = indices[ci];
        if (ci < 2)
        {
          Byte *temp;
          outSizeCur = (SizeT)unpackSize;
          if (outSizeCur != unpackSize)
            return SZ_ERROR_MEM;
          temp = (Byte *)ISzAlloc_Alloc(allocMain, outSizeCur);
          if (!temp && outSizeCur != 0)
            return SZ_ERROR_MEM;
          outBufCur = tempBuf[1 - ci] = temp;
          tempSizes[1 - ci] = outSizeCur;
        }
        else if (ci == 2)
        {
          if (unpackSize > outSize) /* check it */
            return SZ_ERROR_PARAM;
          tempBuf3 = outBufCur = outBuffer + (outSize - (size_t)unpackSize);
          tempSize3 = outSizeCur = (SizeT)unpackSize;
        }
        else
          return SZ_ERROR_UNSUPPORTED;
      }
      offset = packPositions[si];
      inSize = packPositions[(size_t)si + 1] - offset;
      RINOK(LookInStream_SeekTo(inStream, startPos + offset))

      if (coder->MethodID == k_Copy)
      {
        if (inSize != outSizeCur) /* check it */
          return SZ_ERROR_DATA;
        RINOK(SzDecodeCopy(inSize, inStream, outBufCur))
      }
      else if (coder->MethodID == k_LZMA)
      {
        RINOK(SzDecodeLzma(propsData + coder->PropsOffset, coder->PropsSize, inSize, inStream, outBufCur, outSizeCur, allocMain))
      }
    #ifndef Z7_NO_METHOD_LZMA2
      else if (coder->MethodID == k_LZMA2)
      {
        RINOK(SzDecodeLzma2(propsData + coder->PropsOffset, coder->PropsSize, inSize, inStream, outBufCur, outSizeCur, allocMain))
      }
    #endif
    #ifdef Z7_PPMD_SUPPORT
      else if (coder->MethodID == k_PPMD)
      {
        RINOK(SzDecodePpmd(propsData + coder->PropsOffset, coder->PropsSize, inSize, inStream, outBufCur, outSizeCur, allocMain))
      }
    #endif
      else
        return SZ_ERROR_UNSUPPORTED;
    }
    else if (coder->MethodID == k_BCJ2)
    {
      const UInt64 offset = packPositions[1];
      const UInt64 s3Size = packPositions[2] - offset;
      
      if (ci != 3)
        return SZ_ERROR_UNSUPPORTED;
      
      tempSizes[2] = (SizeT)s3Size;
      if (tempSizes[2] != s3Size)
        return SZ_ERROR_MEM;
      tempBuf[2] = (Byte *)ISzAlloc_Alloc(allocMain, tempSizes[2]);
      if (!tempBuf[2] && tempSizes[2] != 0)
        return SZ_ERROR_MEM;
      
      RINOK(LookInStream_SeekTo(inStream, startPos + offset))
      RINOK(SzDecodeCopy(s3Size, inStream, tempBuf[2]))

      if ((tempSizes[0] & 3) != 0 ||
          (tempSizes[1] & 3) != 0 ||
          tempSize3 + tempSizes[0] + tempSizes[1] != outSize)
        return SZ_ERROR_DATA;

      {
        CBcj2Dec p;
        
        p.bufs[0] = tempBuf3;   p.lims[0] = tempBuf3 + tempSize3;
        p.bufs[1] = tempBuf[0]; p.lims[1] = tempBuf[0] + tempSizes[0];
        p.bufs[2] = tempBuf[1]; p.lims[2] = tempBuf[1] + tempSizes[1];
        p.bufs[3] = tempBuf[2]; p.lims[3] = tempBuf[2] + tempSizes[2];
        
        p.dest = outBuffer;
        p.destLim = outBuffer + outSize;
        
        Bcj2Dec_Init(&p);
        RINOK(Bcj2Dec_Decode(&p))

        {
          unsigned i;
          for (i = 0; i < 4; i++)
            if (p.bufs[i] != p.lims[i])
              return SZ_ERROR_DATA;
          if (p.dest != p.destLim || !Bcj2Dec_IsMaybeFinished(&p))
            return SZ_ERROR_DATA;
        }
      }
    }
#if defined(Z7_USE_BRANCH_FILTER)
    else if (ci == 1)
    {
#if !defined(Z7_NO_METHODS_FILTERS)
      if (coder->MethodID == k_Delta)
      {
        if (coder->PropsSize != 1)
          return SZ_ERROR_UNSUPPORTED;
        {
          Byte state[DELTA_STATE_SIZE];
          Delta_Init(state);
          Delta_Decode(state, (unsigned)(propsData[coder->PropsOffset]) + 1, outBuffer, outSize);
        }
        continue;
      }
#endif
     
#ifdef Z7_USE_FILTER_ARM64
      if (coder->MethodID == k_ARM64)
      {
        UInt32 pc = 0;
        if (coder->PropsSize == 4)
        {
          pc = GetUi32(propsData + coder->PropsOffset);
          if (pc & 3)
            return SZ_ERROR_UNSUPPORTED;
        }
        else if (coder->PropsSize != 0)
          return SZ_ERROR_UNSUPPORTED;
        z7_BranchConv_ARM64_Dec(outBuffer, outSize, pc);
        continue;
      }
#endif

#if !defined(Z7_NO_METHODS_FILTERS)
      if (coder->MethodID == k_RISCV)
      {
        UInt32 pc = 0;
        if (coder->PropsSize == 4)
        {
          pc = GetUi32(propsData + coder->PropsOffset);
          if (pc & 1)
            return SZ_ERROR_UNSUPPORTED;
        }
        else if (coder->PropsSize != 0)
          return SZ_ERROR_UNSUPPORTED;
        z7_BranchConv_RISCV_Dec(outBuffer, outSize, pc);
        continue;
      }
#endif
      
#if !defined(Z7_NO_METHODS_FILTERS) || defined(Z7_USE_FILTER_ARMT)
      {
        if (coder->PropsSize != 0)
          return SZ_ERROR_UNSUPPORTED;
       #define CASE_BRA_CONV(isa) case k_ ## isa: Z7_BRANCH_CONV_DEC(isa)(outBuffer, outSize, 0); break; // pc = 0;
        switch (coder->MethodID)
        {
         #if !defined(Z7_NO_METHODS_FILTERS)
          case k_BCJ:
          {
            UInt32 state = Z7_BRANCH_CONV_ST_X86_STATE_INIT_VAL;
            z7_BranchConvSt_X86_Dec(outBuffer, outSize, 0, &state); // pc = 0
            break;
          }
          case k_PPC: Z7_BRANCH_CONV_DEC_2(BranchConv_PPC)(outBuffer, outSize, 0); break; // pc = 0;
          // CASE_BRA_CONV(PPC)
          CASE_BRA_CONV(IA64)
          CASE_BRA_CONV(SPARC)
          CASE_BRA_CONV(ARM)
         #endif
         #if !defined(Z7_NO_METHODS_FILTERS) || defined(Z7_USE_FILTER_ARMT)
          CASE_BRA_CONV(ARMT)
         #endif
          default:
            return SZ_ERROR_UNSUPPORTED;
        }
        continue;
      }
#endif
    } // (c == 1)
#endif // Z7_USE_BRANCH_FILTER
    else
      return SZ_ERROR_UNSUPPORTED;
  }

  return SZ_OK;
}


SRes SzAr_DecodeFolder(const CSzAr *p, UInt32 folderIndex,
    ILookInStreamPtr inStream, UInt64 startPos,
    Byte *outBuffer, size_t outSize,
    ISzAllocPtr allocMain)
{
  SRes res;
  CSzFolder folder;
  CSzData sd;
  
  const Byte *data = p->CodersData + p->FoCodersOffsets[folderIndex];
  sd.Data = data;
  sd.Size = p->FoCodersOffsets[(size_t)folderIndex + 1] - p->FoCodersOffsets[folderIndex];
  
  res = SzGetNextFolderItem(&folder, &sd);
  
  if (res != SZ_OK)
    return res;

  if (sd.Size != 0
      || folder.UnpackStream != p->FoToMainUnpackSizeIndex[folderIndex]
      || outSize != SzAr_GetFolderUnpackSize(p, folderIndex))
    return SZ_ERROR_FAIL;
  {
    unsigned i;
    Byte *tempBuf[3] = { 0, 0, 0};

    res = SzFolder_Decode2(&folder, data,
        &p->CoderUnpackSizes[p->FoToCoderUnpackSizes[folderIndex]],
        p->PackPositions + p->FoStartPackStreamIndex[folderIndex],
        inStream, startPos,
        outBuffer, (SizeT)outSize, allocMain, tempBuf);
    
    for (i = 0; i < 3; i++)
      ISzAlloc_Free(allocMain, tempBuf[i]);

    if (res == SZ_OK)
      if (SzBitWithVals_Check(&p->FolderCRCs, folderIndex))
        if (CrcCalc(outBuffer, outSize) != p->FolderCRCs.Vals[folderIndex])
          res = SZ_ERROR_CRC;

    return res;
  }
}
