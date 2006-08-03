/*
 * Copyright © 2006 Sun Microsystems, Inc., 4150 Network Circle, Santa
 * Clara, California 95054, U.S.A. All rights reserved.
 * 
 * Sun Microsystems, Inc. has intellectual property rights relating to
 * technology embodied in the product that is described in this
 * document. In particular, and without limitation, these intellectual
 * property rights may include one or more of the U.S. patents listed at
 * http://www.sun.com/patents and one or more additional patents or
 * pending patent applications in the U.S. and in other countries.
 * 
 * U.S. Government Rights - Commercial software. Government users are
 * subject to the Sun Microsystems, Inc. standard license agreement and
 * applicable provisions of the FAR and its supplements.
 * 
 * Use is subject to license terms.
 * 
 * This distribution may include materials developed by third parties.
 * 
 * Sun, Sun Microsystems, the Sun logo and Java are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other
 * countries.
 * 
 * This product is covered and controlled by U.S. Export Control laws
 * and may be subject to the export or import laws in other countries.
 * Nuclear, missile, chemical biological weapons or nuclear maritime end
 * uses or end users, whether direct or indirect, are strictly
 * prohibited. Export or reexport to countries subject to U.S. embargo
 * or to entities identified on U.S. export exclusion lists, including,
 * but not limited to, the denied persons and specially designated
 * nationals lists is strictly prohibited.
 * 
 * Copyright © 2006 Sun Microsystems, Inc., 4150 Network Circle, Santa
 * Clara, California 95054, Etats-Unis. Tous droits réservés.
 * 
 * Sun Microsystems, Inc. détient les droits de propriété intellectuels
 * relatifs à la technologie incorporée dans le produit qui est décrit
 * dans ce document. En particulier, et ce sans limitation, ces droits
 * de propriété intellectuelle peuvent inclure un ou plus des brevets
 * américains listés à l'adresse http://www.sun.com/patents et un ou les
 * brevets supplémentaires ou les applications de brevet en attente aux
 * Etats - Unis et dans les autres pays.
 * 
 * L'utilisation est soumise aux termes de la Licence.
 * 
 * Cette distribution peut comprendre des composants développés par des
 * tierces parties.
 * 
 * Sun, Sun Microsystems, le logo Sun et Java sont des marques de
 * fabrique ou des marques déposées de Sun Microsystems, Inc. aux
 * Etats-Unis et dans d'autres pays.
 * 
 * Ce produit est soumis à la législation américaine en matière de
 * contrôle des exportations et peut être soumis à la règlementation en
 * vigueur dans d'autres pays dans le domaine des exportations et
 * importations. Les utilisations, ou utilisateurs finaux, pour des
 * armes nucléaires,des missiles, des armes biologiques et chimiques ou
 * du nucléaire maritime, directement ou indirectement, sont strictement
 * interdites. Les exportations ou réexportations vers les pays sous
 * embargo américain, ou vers des entités figurant sur les listes
 * d'exclusion d'exportation américaines, y compris, mais de manière non
 * exhaustive, la liste de personnes qui font objet d'un ordre de ne pas
 * participer, d'une façon directe ou indirecte, aux exportations des
 * produits ou des services qui sont régis par la législation américaine
 * en matière de contrôle des exportations et la liste de ressortissants
 * spécifiquement désignés, sont rigoureusement interdites.
 */

#include "stable.h"

#include "XMLReader.h"

#include "Platform/IStream.h"

#define XML_UNICODE_WCHAR_T 1
#include <expat.h>

namespace SGS
{
	namespace Internal
	{
		class XMLAdapter
		{
		public:
			static void XMLCALL OnStartElement(void *userData, const XML_Char *name, const XML_Char **atts);
			static void XMLCALL OnEndElement(void *userData, const XML_Char *name);
			static void XMLCALL OnCharacterData(void *userData, const XML_Char *s, int len);
		};
	}
}

SGS::XMLReader::XMLReader(SGS::IStream* pStream) :
	mpStream(pStream),
	mParser(XML_ParserCreate(NULL)),
	mpCurrentElement(NULL)
{
	if (!mParser)
		return;

	XML_SetUserData(mParser, reinterpret_cast<void*>(this));
	XML_SetElementHandler(mParser, &Internal::XMLAdapter::OnStartElement, &Internal::XMLAdapter::OnEndElement);
	XML_SetCharacterDataHandler(mParser, &Internal::XMLAdapter::OnCharacterData);
}

SGS::XMLReader::~XMLReader()
{
	if (mParser != NULL)
		XML_ParserFree(mParser);

	delete mpCurrentElement;
	for (std::list<SGS::XMLElement*>::iterator iter = mElements.begin(); iter != mElements.end(); ++iter)
		delete *iter;
}

bool SGS::XMLReader::IsEOF()
{
	if (!mParser || !mpStream)
		return true;
	else
	{
		Parse();
		return mElements.empty();
	}
}

const SGS::XMLElement& SGS::XMLReader::ReadElement()
{
	//ASSERT(mParser);
	//ASSERT(mpStream);

	Parse();
	//ASSERT(!mElements.empty());

	delete mpCurrentElement;
	mpCurrentElement = mElements.front();
	mElements.pop_front();
	return *mpCurrentElement;
}

class xml_error : public std::exception
{
public:
	xml_error(const wchar_t* /*what*/)
	{
	}
};

#define throwXmlError(xmlFile, xmlLine, what)	throw xml_error(__FILE__, __LINE__, xmlFile, xmlLine, what)

void SGS::XMLReader::Parse()
{
	while (mElements.empty() && !mpStream->IsEOF())
	{
		void* data;
		size_t length;

		char buf[BUFSIZ];
		if (mpStream->CanReadOptimized())
		{
			length = mpStream->ReadOptimized(data, mpStream->GetLength());
		}
		else
		{
			data = buf;
			length = mpStream->Read(buf, sizeof(buf));
		}

		bool done = mpStream->IsEOF();
		if (XML_Parse(mParser, (char*)data, (int)length, done) == XML_STATUS_ERROR) 
		{
			//int line = XML_GetCurrentLineNumber(mParser); 
			throw xml_error(XML_ErrorString(XML_GetErrorCode(mParser)));
		}
	}
}

void XMLCALL SGS::Internal::XMLAdapter::OnStartElement(void *userData, const XML_Char *name, const XML_Char **atts)
{
	SGS::XMLElement* pElement = new SGS::XMLElement; 
	pElement->kind = SGS::kStart; 
	pElement->name = name; 
	while ( *atts != NULL )
	{
		const XML_Char* key		= *atts++; 
		const XML_Char* value	= *atts++; 
		pElement->attributes[key] = value; 
	}

	SGS::XMLReader* pThis = reinterpret_cast<SGS::XMLReader*>(userData);
	pThis->mElements.push_back(pElement); 
}

void XMLCALL SGS::Internal::XMLAdapter::OnEndElement(void *userData, const XML_Char *name)
{
	SGS::XMLElement* pElement = new SGS::XMLElement; 
	pElement->kind = SGS::kEnd; 
	pElement->name = name; 

	SGS::XMLReader* pThis = reinterpret_cast<SGS::XMLReader*>(userData);
	pThis->mElements.push_back(pElement); 
}

void XMLCALL SGS::Internal::XMLAdapter::OnCharacterData(void *userData, const XML_Char *s, int len)
{
	SGS::XMLElement* pElement = new SGS::XMLElement; 
	pElement->kind = SGS::kText; 
	pElement->name = std::wstring( s, len ); 

	SGS::XMLReader* pThis = reinterpret_cast<SGS::XMLReader*>(userData);
	pThis->mElements.push_back(pElement); 
}