package com.kryptnostic.crypto;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cern.colt.bitvector.BitVector;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.kryptnostic.crypto.padding.PaddingStrategy;
import com.kryptnostic.crypto.padding.ZeroPaddingStrategy;
import com.kryptnostic.multivariate.gf2.SimplePolynomialFunction;
import com.kryptnostic.multivariate.polynomial.OptimizedPolynomialFunctionGF2;

/**
 * Public key class used for encryption.
 * 
 * @author Matthew Tamayo-Rios
 */
public class PublicKey {
    private static final String              ENCRYPTER_PROPERTY        = "encrypter";
    private static final String              M_PROPERTY                = "m";
    private static final String              COMPLEXITY_CHAIN_PROPERTY = "complexity-chain";
    private static final String              PADDING_STRATEGY_PROPERTY = "padding-strategy";
    private static final String              LONGS_PER_BLOCK_PROPERTY  = "longs-per-block";

    private static final Logger              logger                    = LoggerFactory.getLogger( PublicKey.class );
    // TODO: Replace with bouncy castle or real number generator.
    private static final Random              r                         = new Random( 0 );
    private final SimplePolynomialFunction   encrypter;
    private final SimplePolynomialFunction   m;
    private final SimplePolynomialFunction[] complexityChain;
    private final PaddingStrategy            paddingStrategy;
    private final int                        longsPerBlock;

    @JsonCreator
    public PublicKey(
            @JsonProperty( ENCRYPTER_PROPERTY ) SimplePolynomialFunction encrypter,
            @JsonProperty( M_PROPERTY ) SimplePolynomialFunction m,
            @JsonProperty( COMPLEXITY_CHAIN_PROPERTY ) SimplePolynomialFunction[] complexityChain,
            @JsonProperty( PADDING_STRATEGY_PROPERTY ) PaddingStrategy paddingStrategy,
            @JsonProperty( LONGS_PER_BLOCK_PROPERTY ) int longsPerBlock ) {
        this.encrypter = encrypter;
        this.m = m;
        this.complexityChain = complexityChain;
        this.paddingStrategy = paddingStrategy;
        this.longsPerBlock = longsPerBlock;
    }

    public PublicKey( PrivateKey privateKey ) {
        this( privateKey, new ZeroPaddingStrategy( privateKey.getE1().rows() >>> 4 ) );
    }

    public PublicKey( PrivateKey privateKey, PaddingStrategy paddingStrategy ) {
        this.paddingStrategy = paddingStrategy;
        int inputLen = privateKey.getE1().cols();
        int outputLen = privateKey.getE1().rows();
        complexityChain = null;
        m = OptimizedPolynomialFunctionGF2.truncatedIdentity( inputLen, outputLen );
        logger.debug( "m: {} -> {}", outputLen, inputLen );

        /*
         * E(m) = E1(m + h + Ag ) + E2(m + h + Bg )
         */

        encrypter = privateKey.encrypt( m );
        logger.debug( "Required input length in bits: {}", encrypter.getInputLength() );
        // 8 bits per byte, 8 bytes per long.
        longsPerBlock = encrypter.getInputLength() >>> 7;
    }

    public Ciphertext encryptIntoEnvelope( byte[] plaintext ) {
        long[] lengthArray = new long[ longsPerBlock << 1 ];

        lengthArray[ 0 ] = plaintext.length;
        for ( int i = 1; i < lengthArray.length; ++i ) {
            lengthArray[ i ] = r.nextLong();
        }

        return new Ciphertext( encrypt( plaintext ), encrypt( lengthArray ) );
    }

    byte[] encrypt( byte[] plaintext ) {
        Preconditions.checkNotNull( plaintext, "Plaintext to be encrypted cannot be null." );

        /*
         * 1) Pad the data so it aligns
         */
        plaintext = paddingStrategy.pad( plaintext );

        ByteBuffer buffer = ByteBuffer.wrap( plaintext );
        ByteBuffer outBuf = ByteBuffer.allocate( plaintext.length << 1 );

        int blockLen = longsPerBlock << 1;
        while ( buffer.remaining() > 0 ) {
            long[] lpt = new long[ blockLen ];

            for ( int i = 0; i < longsPerBlock; ++i ) {
                lpt[ i ] = buffer.getLong();
            }

            for ( int i = longsPerBlock; i < blockLen; ++i ) {
                lpt[ i ] = 0L;// r.nextLong();
            }

            long[] ciphertext = encrypt( lpt );
            for ( long lct : ciphertext ) {
                outBuf.putLong( lct );
            }
        }

        return outBuf.array();
    }

    long[] encrypt( long[] plaintext ) {
        logger.debug( "Expected plaintext block length: {}", encrypter.getInputLength() );
        logger.debug( "Observed plaintext block length: {}", plaintext.length * 8 * 8 );
        Preconditions.checkArgument(
                ( plaintext.length << 3 ) == ( encrypter.getInputLength() >>> 3 ),
                "Cannot directly encrypt block of incorrect length." );

        BitVector result = encrypter.apply( new BitVector( plaintext, encrypter.getInputLength() ) );
        if ( logger.isDebugEnabled() ) {
            for ( long l : result.elements() ) {
                logger.debug( "Wrote the following ciphertext long: {}", l );
            }
        }
        return result.elements();
    }

    public SimplePolynomialFunction getEncrypter() {
        return encrypter;
    }

    public SimplePolynomialFunction getM() {
        return m;
    }

    public SimplePolynomialFunction[] getComplexityChain() {
        return complexityChain;
    }

    public PaddingStrategy getPaddingStrategy() {
        return paddingStrategy;
    }

    public int getLongsPerBlock() {
        return longsPerBlock;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode( complexityChain );
        result = prime * result + ( ( encrypter == null ) ? 0 : encrypter.hashCode() );
        result = prime * result + longsPerBlock;
        result = prime * result + ( ( m == null ) ? 0 : m.hashCode() );
        result = prime * result + ( ( paddingStrategy == null ) ? 0 : paddingStrategy.hashCode() );
        return result;
    }

    @Override
    public boolean equals( Object obj ) {
        if ( this == obj ) {
            return true;
        }
        if ( obj == null ) {
            return false;
        }
        if ( !( obj instanceof PublicKey ) ) {
            return false;
        }
        PublicKey other = (PublicKey) obj;
        if ( !Arrays.equals( complexityChain, other.complexityChain ) ) {
            return false;
        }
        if ( encrypter == null ) {
            if ( other.encrypter != null ) {
                return false;
            }
        } else if ( !encrypter.equals( other.encrypter ) ) {
            return false;
        }
        if ( longsPerBlock != other.longsPerBlock ) {
            return false;
        }
        if ( m == null ) {
            if ( other.m != null ) {
                return false;
            }
        } else if ( !m.equals( other.m ) ) {
            return false;
        }
        if ( paddingStrategy == null ) {
            if ( other.paddingStrategy != null ) {
                return false;
            }
        } else if ( !paddingStrategy.equals( other.paddingStrategy ) ) {
            return false;
        }
        return true;
    }
}
