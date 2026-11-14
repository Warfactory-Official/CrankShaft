// Exact dyadic predicates for the actual float32 bake vertices. A product of three finite floats has at most
// 72 mantissa bits and an exponent in [-447,312]. Twenty-four terms fit in 836 signed bits; 27 words cannot wrap.
layout(std430,binding=9) buffer FarLayerQuadSurfaces { FarLayerSurface _flw_quadSurfaces[]; };
layout(std430,binding=14) readonly buffer FarLayerQuadCorners { uvec4 _flw_quadCorners[]; };
uniform uint sourceCount;

void _flw_farQuadTerm(inout uint sum[27],vec3 value,bool negative) {
    uvec3 bits=floatBitsToUint(value),mantissa=bits&0x7fffffu;
    ivec3 exponent=ivec3((bits>>23u)&255u);
    for(int a=0;a<3;a++) {
        if(exponent[a]!=0) {mantissa[a]|=0x800000u;exponent[a]-=150;}
        else exponent[a]=-149;
    }
    if(any(equal(mantissa,uvec3(0u)))) return;
    negative=negative!=(((bits.x^bits.y^bits.z)&0x80000000u)!=0u);
    uint hi,lo,top,middle,low,upper;
    umulExtended(mantissa.x,mantissa.y,hi,lo);
    umulExtended(lo,mantissa.z,middle,low);
    umulExtended(hi,mantissa.z,top,upper);
    uint previous=middle;middle+=upper;top+=uint(middle<previous);
    uint shift=uint(exponent.x+exponent.y+exponent.z+447),offset=shift>>5u,part=shift&31u;
    uvec4 words=uvec4(low,middle,top,0u);
    if(part!=0u) words=uvec4(low<<part,(middle<<part)|(low>>(32u-part)),
            (top<<part)|(middle>>(32u-part)),top>>(32u-part));
    uint carry=0u;
    for(uint a=offset;a<27u;a++) {
        uint term=a-offset<4u?words[a-offset]:0u,old=sum[a],next;
        if(negative) {
            next=old-term;
            uint borrow=uint(old<term),before=next;
            next-=carry;
            carry=borrow|uint(before<carry);
        } else {
            next=old+term;
            uint overflow=uint(next<old),before=next;
            next+=carry;
            carry=overflow|uint(next<before);
        }
        sum[a]=next;
        if(a>=offset+3u&&carry==0u) break;
    }
}
int _flw_farQuadSign(uint sum[27]) {
    uint occupied=0u;
    for(int a=0;a<27;a++) occupied|=sum[a];
    return occupied==0u?0:(sum[26]&0x80000000u)!=0u?-1:1;
}
bool _flw_farQuadEqual(float a,float b) {
    uint x=floatBitsToUint(a),y=floatBitsToUint(b);
    return x==y||((x|y)&0x7fffffffu)==0u;
}
bool _flw_farQuadSubnormal(vec3 value) {
    uvec3 bits=floatBitsToUint(value)&0x7fffffffu;
    return (bits.x>0u&&bits.x<0x800000u)||(bits.y>0u&&bits.y<0x800000u)
            ||(bits.z>0u&&bits.z<0x800000u);
}
bool _flw_farQuadPlanar(vec3 p[4]) {
    for(int a=0;a<3;a++) if(_flw_farQuadEqual(p[0][a],p[1][a])
            &&_flw_farQuadEqual(p[0][a],p[2][a])&&_flw_farQuadEqual(p[0][a],p[3][a])) return true;
    if(!_flw_farQuadSubnormal(p[0])&&!_flw_farQuadSubnormal(p[1])
            &&!_flw_farQuadSubnormal(p[2])&&!_flw_farQuadSubnormal(p[3])) {
        precise dvec3 a=dvec3(p[1])-dvec3(p[0]),b=dvec3(p[2])-dvec3(p[0]),c=dvec3(p[3])-dvec3(p[0]);
        precise double p0=(a.x*b.y)*c.z,p1=(a.y*b.z)*c.x,p2=(a.z*b.x)*c.y;
        precise double n0=(a.z*b.y)*c.x,n1=(a.y*b.x)*c.z,n2=(a.x*b.z)*c.y;
        precise double determinant=((((p0+p1)+p2)-n0)-n1)-n2;
        // Three subtractions and two multiplications per term, then five sums: this exceeds gamma(10).
        double bound=(abs(p0)+abs(p1)+abs(p2)+abs(n0)+abs(n1)+abs(n2))*3.552713678800501e-15lf;
        if(abs(determinant)>bound) return false;
    }
    uint sum[27];
    for(int a=0;a<27;a++) sum[a]=0u;
    for(uint omitted=0u;omitted<4u;omitted++) {
        vec3 a=p[(omitted+1u)%4u],b=p[(omitted+2u)%4u],c=p[(omitted+3u)%4u];
        bool negative=(omitted&1u)!=0u;
        _flw_farQuadTerm(sum,vec3(a.x,b.y,c.z),negative);
        _flw_farQuadTerm(sum,vec3(a.y,b.z,c.x),negative);
        _flw_farQuadTerm(sum,vec3(a.z,b.x,c.y),negative);
        _flw_farQuadTerm(sum,vec3(a.z,b.y,c.x),!negative);
        _flw_farQuadTerm(sum,vec3(a.y,b.x,c.z),!negative);
        _flw_farQuadTerm(sum,vec3(a.x,b.z,c.y),!negative);
    }
    return _flw_farQuadSign(sum)==0;
}
int _flw_farQuadSide(vec3 a,vec3 b,vec3 c,uint u,uint v) {
    if(_flw_farQuadEqual(a[u],b[u])&&_flw_farQuadEqual(a[u],c[u])
            ||_flw_farQuadEqual(a[v],b[v])&&_flw_farQuadEqual(a[v],c[v])) return 0;
    // Each product of two float mantissas is exact in double; certify a nonzero sign before the integer path.
    precise double p0=double(a[u])*double(b[v]),p1=double(b[u])*double(c[v]),p2=double(c[u])*double(a[v]);
    precise double n0=double(a[v])*double(b[u]),n1=double(b[v])*double(c[u]),n2=double(c[v])*double(a[u]);
    precise double determinant=((((p0+p1)+p2)-n0)-n1)-n2;
    double bound=(abs(p0)+abs(p1)+abs(p2)+abs(n0)+abs(n1)+abs(n2))*1.7763568394002505e-15lf;
    if(!_flw_farQuadSubnormal(a)&&!_flw_farQuadSubnormal(b)&&!_flw_farQuadSubnormal(c)
            &&abs(determinant)>bound) return determinant>0.0?1:-1;
    uint sum[27];
    for(int i=0;i<27;i++) sum[i]=0u;
    _flw_farQuadTerm(sum,vec3(a[u],b[v],1.0),false);
    _flw_farQuadTerm(sum,vec3(b[u],c[v],1.0),false);
    _flw_farQuadTerm(sum,vec3(c[u],a[v],1.0),false);
    _flw_farQuadTerm(sum,vec3(a[v],b[u],1.0),true);
    _flw_farQuadTerm(sum,vec3(b[v],c[u],1.0),true);
    _flw_farQuadTerm(sum,vec3(c[v],a[u],1.0),true);
    return _flw_farQuadSign(sum);
}
void _flw_farCertifyQuad(uint source) {
    if(source>=sourceCount||_flw_quadCorners[source*2u].w!=source+1u) return;
    vec3 p[4];
    for(uint corner=0u;corner<4u;corner++) p[corner]=uintBitsToFloat(_flw_quadCorners[source*2u+corner].xyz);
    if(!_flw_farQuadPlanar(p)) return;
    bool continuous=false;
    for(uint axis=0u;axis<3u;axis++) {
        int first=_flw_farQuadSide(p[0],p[1],p[2],(axis+1u)%3u,(axis+2u)%3u);
        if(first==0) continue;
        // Canonical triangles share the identical 0--2 edge in opposite directions. Equal orientation puts their
        // interiors on opposite sides of that edge, excluding bowties and coplanar overlap.
        continuous=first==_flw_farQuadSide(p[2],p[3],p[0],(axis+1u)%3u,(axis+2u)%3u);
        break;
    }
    if(!continuous) return;
    if(_flw_quadSurfaces[source].info.w==0u) {
        if(_flw_quadSurfaces[source+1u].info.w==0u) return;
        // The first triangle may lie wholly outside this brick's filter support. Its certified common plane is
        // still needed for the group's canonical intersection; the captured partner supplies that same plane.
        _flw_quadSurfaces[source].plane=_flw_quadSurfaces[source+1u].plane;
        _flw_quadSurfaces[source].info.xy=uvec2(_flw_quadSurfaces[source+1u].info.x,source);
    }
    _flw_quadSurfaces[source].info.z=source+1u;
    _flw_quadSurfaces[source+1u].info.z=source+1u;
}
